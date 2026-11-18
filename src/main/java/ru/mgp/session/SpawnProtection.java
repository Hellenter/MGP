package ru.mgp.session;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import ru.mgp.MGPPlugin;
import ru.mgp.util.Msg;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Защита после возрождения: несколько секунд игрок не получает урона (кроме пустоты).
 * Длительность — settings.spawn-protection режима (сек., можно дробные; 0 — выключено).
 * Защита снимается раньше, если игрок сам ударил или выстрелил.
 * Пока действует — счётчик в action bar и искры вокруг игрока.
 */
public final class SpawnProtection implements Listener {
    public static final double DEFAULT_SECONDS = 3;

    private final MGPPlugin plugin;
    private final Map<UUID, Long> until = new HashMap<>();
    private BukkitTask task;

    public SpawnProtection(MGPPlugin plugin) { this.plugin = plugin; }

    /** Секунды защиты для режима сессии. */
    public static double seconds(GameSession s) {
        Object o = s.mode.settings.get("spawn-protection");
        if (o instanceof Number n) return Math.max(0, n.doubleValue());
        if (o != null) {
            try { return Math.max(0, Double.parseDouble(String.valueOf(o).trim().replace(',', '.'))); }
            catch (NumberFormatException ignored) {}
        }
        return DEFAULT_SECONDS;
    }

    public void protect(Player p, GameSession s) {
        double sec = seconds(s);
        if (sec <= 0 || !p.isOnline()) return;
        until.put(p.getUniqueId(), System.currentTimeMillis() + (long) (sec * 1000));
        p.playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.6f, 1.6f);
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 2L);
    }

    public boolean isProtected(Player p) {
        Long u = until.get(p.getUniqueId());
        return u != null && u > System.currentTimeMillis();
    }

    /** Снять защиту; notify — показать игроку, что она закончилась. */
    public void end(Player p, boolean notify) {
        if (until.remove(p.getUniqueId()) == null || !notify || !p.isOnline()) return;
        p.sendActionBar(Msg.parse("<gray>Защита после возрождения снята"));
        p.playSound(p.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.5f, 1.6f);
    }

    public void clear(UUID u) { until.remove(u); }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> e : new HashMap<>(until).entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) { until.remove(e.getKey()); continue; }
            GameSession s = plugin.sessions().of(p);
            if (s == null || !s.isPlaying() || !s.isAlive(p)) { until.remove(e.getKey()); continue; }
            long left = e.getValue() - now;
            if (left <= 0) { end(p, true); continue; }
            p.sendActionBar(Msg.parse("<aqua>🛡 Защита после возрождения <white>"
                    + String.format(Locale.ROOT, "%.1f", left / 1000.0) + " с <dark_gray>(атака снимет)"));
            Location l = p.getLocation();
            double a = (now / 150.0) % (Math.PI * 2);
            for (int i = 0; i < 2; i++) {
                double ang = a + Math.PI * i;
                p.getWorld().spawnParticle(Particle.END_ROD, l.clone().add(Math.cos(ang) * 0.7, 1.0, Math.sin(ang) * 0.7), 1, 0, 0, 0, 0);
            }
        }
        if (until.isEmpty() && task != null) { task.cancel(); task = null; }
    }

    private static Player attacker(EntityDamageEvent e) {
        if (!(e instanceof EntityDamageByEntityEvent ee)) return null;
        Entity d = ee.getDamager();
        if (d instanceof Player p) return p;
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        if (d instanceof TNTPrimed t && t.getSource() instanceof Player p) return p;
        return null;
    }

    /** Защищённый не получает урона (пустота убивает как обычно). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player v && e.getCause() != EntityDamageEvent.DamageCause.VOID && isProtected(v)) {
            e.setCancelled(true);
            Player a = attacker(e);
            if (a != null && !a.equals(v)) a.sendActionBar(Msg.parse("<aqua>🛡 <gray>У " + Msg.esc(v.getName()) + " защита после возрождения"));
        }
    }

    /** Защищённый сам нанёс урон — защита снимается (засчитываем только прошедшие удары: свои/подготовка не в счёт). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageEvent e) {
        Player a = attacker(e);
        if (a != null && !a.equals(e.getEntity()) && until.containsKey(a.getUniqueId())) end(a, true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(ProjectileLaunchEvent e) {
        if (e.getEntity().getShooter() instanceof Player p && until.containsKey(p.getUniqueId())) end(p, true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) { clear(e.getPlayer().getUniqueId()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { clear(e.getPlayer().getUniqueId()); }
}
