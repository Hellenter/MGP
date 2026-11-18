package ru.mgp.guard;

import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.scheduler.BukkitTask;
import ru.mgp.MGPPlugin;
import ru.mgp.session.GameSession;
import ru.mgp.util.Msg;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Анти-AFK в матчах: живой игрок идущей игры, который N секунд ничего не делает (не ходит, не крутит камерой,
 * не бьёт, не ставит блоки, не пишет в чат), сначала получает предупреждение, затем отправляется в хаб.
 * Время — config.yml → protection.afk.timeout, режим может задать своё: settings.afk-timeout (0 — выключено).
 * Боты (/mgp bot) и режимы, которые сами просят не трогать игрока (прячущиеся в прятках), не проверяются.
 */
public final class AntiAfk implements Listener {
    private final MGPPlugin plugin;
    /** Последняя активность (мс). Пишется и из асинхронного чата. */
    private final Map<UUID, Long> last = new ConcurrentHashMap<>();
    private final Set<UUID> warned = new HashSet<>();
    private BukkitTask task;

    public AntiAfk(MGPPlugin plugin) { this.plugin = plugin; }

    public void start() {
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void shutdown() {
        if (task != null) { task.cancel(); task = null; }
        last.clear();
        warned.clear();
    }

    private ConfigurationSection cfg() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("protection.afk");
        return c != null ? c : plugin.getConfig().createSection("protection.afk");
    }

    /** Таймаут для режима в секундах; 0 — выключено. */
    int timeout(GameSession s) {
        if (!cfg().getBoolean("enabled", true)) return 0;
        if (s.mode.settings.contains("afk-timeout")) return Math.max(0, s.mode.settings.getInt("afk-timeout"));
        return Math.max(0, cfg().getInt("timeout", 90));
    }

    private boolean watched(Player p, GameSession s) {
        return s != null && s.isPlaying() && s.isAlive(p) && !plugin.bots().isBot(p)
                && !p.hasPermission("mgp.afk.bypass") && !s.logic().afkExempt(p);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        Set<UUID> seen = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            GameSession s = plugin.sessions().of(p);
            int timeout = s == null ? 0 : timeout(s);
            if (timeout <= 0 || !watched(p, s)) continue;
            UUID u = p.getUniqueId();
            seen.add(u);
            long idle = (now - last.computeIfAbsent(u, k -> now)) / 1000;
            int warn = Math.min(Math.max(0, cfg().getInt("warn", 20)), timeout - 1);
            if (idle >= timeout) {
                kick(p, s);
            } else if (warn > 0 && idle >= timeout - warn) {
                long left = timeout - idle;
                if (warned.add(u)) {
                    Msg.title(p, "<red><bold>Вы AFK?", "<yellow>Пошевелитесь, иначе через " + left + " сек. вернётесь в лобби");
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.6f);
                }
                p.sendActionBar(Msg.parse("<red>Бездействие: <white>" + left + " сек. <gray>до выхода в лобби"));
            }
        }
        // не в игре / погиб / игра закончилась — счёт начнётся заново
        last.keySet().retainAll(seen);
        warned.retainAll(seen);
    }

    private void kick(Player p, GameSession s) {
        UUID u = p.getUniqueId();
        last.remove(u);
        warned.remove(u);
        plugin.getLogger().info("[AFK] " + p.getName() + " отправлен в хаб из игры " + s.mode.id + " #" + s.id);
        s.broadcast("<gray>" + Msg.esc(p.getName()) + " <yellow>отправлен в лобби за бездействие");
        plugin.sessions().leave(p);
        Msg.send(p, "<yellow>Вы слишком долго бездействовали и вернулись в лобби.");
    }

    private void active(Player p) {
        UUID u = p.getUniqueId();
        if (!last.containsKey(u)) return; // не отслеживается
        last.put(u, System.currentTimeMillis());
        if (Bukkit.isPrimaryThread() && warned.remove(u)) {
            p.sendActionBar(Msg.parse("<green>Вы снова в игре"));
            p.clearTitle();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location f = e.getFrom(), t = e.getTo();
        // свой ход или поворот камеры; падение/течение воды без поворота камеры не в счёт
        boolean turned = Math.abs(f.getYaw() - t.getYaw()) > 0.5f || Math.abs(f.getPitch() - t.getPitch()) > 0.5f;
        boolean walked = (f.getX() != t.getX() || f.getZ() != t.getZ()) && e.getPlayer().isOnGround();
        if (turned || walked) active(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) { active(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAttack(PrePlayerAttackEntityEvent e) { active(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent e) { active(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent e) { active(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHeld(PlayerItemHeldEvent e) { active(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSneak(PlayerToggleSneakEvent e) { active(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent e) { if (e.getWhoClicked() instanceof Player p) active(p); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent e) { active(e.getPlayer()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        last.remove(e.getPlayer().getUniqueId());
        warned.remove(e.getPlayer().getUniqueId());
    }
}
