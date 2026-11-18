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
 * Анти-AFK. Игрок, который N секунд ничего не делает (не ходит, не крутит камерой, не бьёт, не ставит блоки,
 * не пишет в чат), сначала получает предупреждение, затем:
 * в игре (живой) — в хаб через protection.afk.timeout (режим может задать своё: settings.afk-timeout, 0 — выкл.);
 * в лобби ожидания — в хаб, в хабе — отключение от сервера, через protection.afk.lobby-timeout.
 * Не проверяются: боты, админы (mgp.admin), право mgp.afk.bypass, прячущиеся в прятках, наблюдатели.
 */
public final class AntiAfk implements Listener {
    private final MGPPlugin plugin;
    /** Последняя активность (мс). Пишется и из асинхронного чата. */
    private final Map<UUID, Long> last = new ConcurrentHashMap<>();
    private final Set<UUID> warned = new HashSet<>();
    private final Map<UUID, Place> places = new java.util.HashMap<>();
    private BukkitTask task;

    public AntiAfk(MGPPlugin plugin) { this.plugin = plugin; }

    public void start() {
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void shutdown() {
        if (task != null) { task.cancel(); task = null; }
        last.clear();
        warned.clear();
        places.clear();
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

    /** Таймаут в лобби (хаб и лобби ожидания), сек.; 0 — выключено. */
    int lobbyTimeout() {
        if (!cfg().getBoolean("enabled", true)) return 0;
        return Math.max(0, cfg().getInt("lobby-timeout", 300));
    }

    private boolean exempt(Player p) {
        return plugin.bots().isBot(p) || p.hasPermission("mgp.admin") || p.hasPermission("mgp.afk.bypass");
    }

    /** Где сейчас игрок с точки зрения AFK. */
    private enum Place { MATCH, WAITING, HUB, NONE }

    private Place place(Player p, GameSession s) {
        if (s == null) return Place.HUB;
        return switch (s.state()) {
            case CREATING, WAITING, STARTING -> Place.WAITING;
            case PLAYING -> s.isAlive(p) && !s.logic().afkExempt(p) ? Place.MATCH : Place.NONE;
            default -> Place.NONE; // наблюдатель, конец игры
        };
    }

    private void tick() {
        long now = System.currentTimeMillis();
        Set<UUID> seen = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (exempt(p)) continue;
            GameSession s = plugin.sessions().of(p);
            Place where = place(p, s);
            int timeout = switch (where) {
                case MATCH -> timeout(s);
                case WAITING, HUB -> lobbyTimeout();
                case NONE -> 0;
            };
            if (timeout <= 0) continue;
            UUID u = p.getUniqueId();
            seen.add(u);
            if (places.put(u, where) != where) { last.put(u, now); warned.remove(u); } // новое место — счёт заново
            long idle = (now - last.computeIfAbsent(u, k -> now)) / 1000;
            int warn = Math.min(Math.max(0, cfg().getInt("warn", 20)), timeout - 1);
            String outcome = where == Place.HUB ? "отключитесь от сервера" : "вернётесь в лобби";
            if (idle >= timeout) {
                kick(p, s, where);
            } else if (warn > 0 && idle >= timeout - warn) {
                long left = timeout - idle;
                if (warned.add(u)) {
                    Msg.title(p, "<red><bold>Вы AFK?", "<yellow>Пошевелитесь, иначе через " + left + " сек. " + outcome);
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.6f);
                }
                p.sendActionBar(Msg.parse("<red>Бездействие: <white>" + left + " сек. <gray>— потом " + outcome));
            }
        }
        // сменил место (лобби → игра, погиб, игра закончилась) — счёт начнётся заново
        last.keySet().retainAll(seen);
        warned.retainAll(seen);
        places.keySet().retainAll(seen);
    }

    private void kick(Player p, GameSession s, Place where) {
        UUID u = p.getUniqueId();
        last.remove(u);
        warned.remove(u);
        switch (where) {
            case MATCH -> {
                plugin.getLogger().info("[AFK] " + p.getName() + " отправлен в хаб из игры " + s.mode.id + " #" + s.id);
                s.broadcast("<gray>" + Msg.esc(p.getName()) + " <yellow>отправлен в лобби за бездействие");
                plugin.sessions().leave(p);
                Msg.send(p, "<yellow>Вы слишком долго бездействовали и вернулись в лобби.");
            }
            case WAITING -> {
                plugin.getLogger().info("[AFK] " + p.getName() + " отправлен в хаб из лобби ожидания " + s.mode.id + " #" + s.id);
                plugin.sessions().leave(p);
                Msg.send(p, "<yellow>Вы слишком долго бездействовали в лобби ожидания и вернулись в хаб.");
            }
            case HUB -> {
                plugin.getLogger().info("[AFK] " + p.getName() + " отключён за бездействие в хабе");
                p.kick(Msg.parse("<yellow>Вы отключены за бездействие (AFK)"));
            }
            default -> {}
        }
    }

    private void active(Player p) {
        UUID u = p.getUniqueId();
        if (!last.containsKey(u)) return; // не отслеживается
        last.put(u, System.currentTimeMillis());
        if (Bukkit.isPrimaryThread() && warned.remove(u)) {
            p.sendActionBar(Msg.parse("<green>Отсчёт бездействия сброшен"));
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
        places.remove(e.getPlayer().getUniqueId());
    }
}
