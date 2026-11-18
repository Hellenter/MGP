package ru.mgp.guard;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import ru.mgp.MGPPlugin;
import ru.mgp.session.GameSession;
import ru.mgp.util.Msg;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Простой античит для миниигр: только живые игроки идущих игр, только явные нарушения.
 * Ничего не банит и не откатывает — пишет админам (право mgp.admin) и в plugins/MGP/anticheat.log.
 * <ul>
 *   <li>Полёт — долго в воздухе без опоры и без падения (зависание или подъём).</li>
 *   <li>Скорость — горизонтальная скорость за секунду сильно выше возможной с учётом эффектов.</li>
 *   <li>Дальность удара — удар по сущности дальше дистанции взаимодействия (с запасом на пинг).</li>
 * </ul>
 * Выход из play-зоны уже не даёт сделать барьер {@link ru.mgp.session.PlayZone}.
 * После отбрасывания, телепорта, возрождения, смены режима проверки движения на пару секунд отключаются.
 * Боты (/mgp bot) и игроки с правом mgp.anticheat.bypass не проверяются.
 */
public final class AntiCheat implements Listener {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MGPPlugin plugin;
    private final Map<UUID, Data> data = new HashMap<>();

    private static final class Data {
        long exemptUntil;        // мс: проверки движения отключены (отбросило, телепорт...)
        long iceUntil;           // мс: недавно стоял на льду/слизи — скорость не проверяем
        boolean slimeJump;       // эта прыжковая дуга началась со слизи/кровати — полёт не проверяем
        int airSamples;          // подряд пакетов движения в воздухе без снижения
        double windowDist;       // скорость: путь в текущем окне
        int windowSamples;
        long windowStart;
        int speedStrikes;        // окон подряд с превышением
        final Deque<Long> reachHits = new ArrayDeque<>();
        final Map<String, Long> lastAlert = new HashMap<>();
    }

    public AntiCheat(MGPPlugin plugin) { this.plugin = plugin; }

    private ConfigurationSection cfg() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("protection.anticheat");
        return c != null ? c : plugin.getConfig().createSection("protection.anticheat");
    }

    private boolean on(String check) { return cfg().getBoolean("enabled", true) && cfg().getBoolean(check, true); }

    private Data d(Player p) { return data.computeIfAbsent(p.getUniqueId(), k -> new Data()); }

    /** Проверяем ли игрока сейчас; возвращает сессию или null. */
    private GameSession watched(Player p) {
        if (plugin.bots().isBot(p) || p.hasPermission("mgp.anticheat.bypass")) return null;
        GameSession s = plugin.sessions().of(p);
        if (s == null || !s.isPlaying() || !s.isAlive(p) || !p.getWorld().equals(s.gameWorld())) return null;
        GameMode gm = p.getGameMode();
        if (gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) return null;
        return s;
    }

    public void exempt(Player p, long ms) {
        Data d = d(p);
        d.exemptUntil = Math.max(d.exemptUntil, System.currentTimeMillis() + ms);
        d.airSamples = 0;
        d.speedStrikes = 0;
        d.windowDist = 0;
        d.windowSamples = 0;
        d.windowStart = System.currentTimeMillis();
    }

    // ---------------------------------------------------------------- движение

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        GameSession s = watched(p);
        if (s == null) return;
        Data d = d(p);
        long now = System.currentTimeMillis();
        Location from = e.getFrom(), to = e.getTo();
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return; // только поворот / барьер
        if (p.getAllowFlight() || p.isFlying() || p.isGliding() || p.isRiptiding() || p.isInsideVehicle()
                || p.hasPotionEffect(PotionEffectType.LEVITATION) || p.hasPotionEffect(PotionEffectType.SLOW_FALLING)) {
            exempt(p, 2000);
            return;
        }
        if (now < d.exemptUntil) return;

        Surroundings env = scan(p, to);
        if (env.special) { // вода, лава, паутина, лестница, лиана, строительные леса...
            d.airSamples = 0;
            d.iceUntil = now + 1000;
        }
        if (env.slippery) d.iceUntil = now + 2500;

        // --- полёт
        if (env.ground || env.special) {
            d.airSamples = 0;
            d.slimeJump = env.bouncy;
        } else if (to.getY() - from.getY() >= -0.03 && !d.slimeJump) {
            d.airSamples++;
            int limit = 20 + 4 * amplifier(p, PotionEffectType.JUMP_BOOST);
            if (d.airSamples > limit && on("fly")) {
                flag(p, s, "fly", "полёт", "в воздухе без снижения " + d.airSamples + " шагов подряд, высота над опорой "
                        + String.format(Locale.ROOT, "%.1f", heightAboveGround(to)));
                d.airSamples = 0;
            }
        }

        // --- скорость
        if (d.windowStart == 0) d.windowStart = now;
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        d.windowDist += Math.sqrt(dx * dx + dz * dz);
        d.windowSamples++;
        long span = now - d.windowStart;
        if (span >= 1000) {
            // пакеты от лагающего клиента приходят пачкой — время окна не меньше, чем шагов × 50 мс
            double secs = Math.max(span, d.windowSamples * 50L) / 1000.0;
            double bps = d.windowDist / secs;
            double max = maxSpeed(p);
            if (bps > max && now >= d.iceUntil && on("speed")) {
                if (++d.speedStrikes >= 2) {
                    flag(p, s, "speed", "скорость", String.format(Locale.ROOT, "%.1f блок/с при допустимых %.1f", bps, max));
                    d.speedStrikes = 0;
                }
            } else d.speedStrikes = 0;
            d.windowDist = 0;
            d.windowSamples = 0;
            d.windowStart = now;
        }
    }

    /** Допустимая горизонтальная скорость, блок/с: спринт с прыжками ≈ 7.1 при скорости 0.13 (спринт), +25% запаса. */
    private double maxSpeed(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.MOVEMENT_SPEED);
        double attr = a != null ? Math.max(a.getValue(), 0.13) : 0.13;
        double mult = cfg().getDouble("speed-tolerance", 1.25);
        return 7.2 * (attr / 0.1) * mult;
    }

    private static int amplifier(Player p, PotionEffectType t) {
        PotionEffect e = p.getPotionEffect(t);
        return e == null ? 0 : e.getAmplifier() + 1;
    }

    private record Surroundings(boolean ground, boolean special, boolean slippery, boolean bouncy) {}

    /** Опора под ногами (по блокам сервера, не по флагу клиента) и особые блоки вокруг. */
    private static Surroundings scan(Player p, Location at) {
        World w = at.getWorld();
        BoundingBox bb = p.getBoundingBox().shift(at.toVector().subtract(p.getLocation().toVector()));
        boolean ground = false, special = false, slippery = false, bouncy = false;
        int x0 = floor(bb.getMinX() - 0.3), x1 = floor(bb.getMaxX() + 0.3);
        int z0 = floor(bb.getMinZ() - 0.3), z1 = floor(bb.getMaxZ() + 0.3);
        int y0 = floor(bb.getMinY() - 0.6), y1 = floor(bb.getMaxY() + 0.2);
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) for (int y = y0; y <= y1; y++) {
            Block b = w.getBlockAt(x, y, z);
            Material m = b.getType();
            if (m.isAir()) continue;
            String n = m.name();
            if (b.isLiquid() || m == Material.COBWEB || m == Material.SCAFFOLDING || m == Material.POWDER_SNOW
                    || m == Material.BUBBLE_COLUMN || m == Material.LADDER || m == Material.VINE || n.endsWith("_VINES")
                    || n.endsWith("VINES_PLANT") || m == Material.SWEET_BERRY_BUSH || m == Material.HONEY_BLOCK
                    || b.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged()) special = true;
            if (y <= floor(bb.getMinY())) {
                if (n.contains("ICE")) slippery = true;
                if (m == Material.SLIME_BLOCK || n.endsWith("_BED")) { bouncy = true; slippery = true; }
            }
            // опора: твёрдый блок под ногами в пределах 0.6 блока (заборы, стены, ковры, ступени)
            if (!b.isPassable() && y <= floor(bb.getMinY() + 0.01) && overlapsBelow(b, bb)) ground = true;
        }
        return new Surroundings(ground, special, slippery, bouncy);
    }

    private static boolean overlapsBelow(Block b, BoundingBox bb) {
        // по горизонтали почти без запаса: иначе стена рядом засчитывалась бы как опора
        BoundingBox feet = new BoundingBox(bb.getMinX() - 0.05, bb.getMinY() - 0.6, bb.getMinZ() - 0.05,
                bb.getMaxX() + 0.05, bb.getMinY() + 0.01, bb.getMaxZ() + 0.05);
        for (BoundingBox part : b.getCollisionShape().getBoundingBoxes())
            if (part.shift(b.getX(), b.getY(), b.getZ()).overlaps(feet)) return true;
        return false;
    }

    private static double heightAboveGround(Location at) {
        World w = at.getWorld();
        int x = at.getBlockX(), z = at.getBlockZ();
        for (int y = at.getBlockY(); y > w.getMinHeight(); y--)
            if (!w.getBlockAt(x, y, z).isPassable()) return at.getY() - (y + 1);
        return at.getY() - w.getMinHeight();
    }

    private static int floor(double v) { return (int) Math.floor(v); }

    // ---------------------------------------------------------------- дальность удара

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(PrePlayerAttackEntityEvent e) {
        Player p = e.getPlayer();
        if (!on("reach")) return;
        GameSession s = watched(p);
        if (s == null) return;
        Entity t = e.getAttacked();
        if (!t.getWorld().equals(p.getWorld())) return;
        double dist = distanceToBox(p.getEyeLocation().toVector(), t.getBoundingBox());
        AttributeInstance a = p.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        double range = a != null ? a.getValue() : 3.0;
        // запас: 0.6 блока + сколько цель может пробежать за время пинга
        double limit = range + cfg().getDouble("reach-margin", 0.6) + Math.min(Math.max(p.getPing(), 0), 300) / 1000.0 * 6;
        if (dist <= limit) return;
        Data d = d(p);
        long now = System.currentTimeMillis();
        d.reachHits.addLast(now);
        while (!d.reachHits.isEmpty() && now - d.reachHits.peekFirst() > 10_000) d.reachHits.pollFirst();
        // одиночный удар на грани — бывает при лаге; сообщаем о повторе или о явном превышении
        if (d.reachHits.size() >= 2 || dist > limit + 1.5) {
            flag(p, s, "reach", "дальность удара", String.format(Locale.ROOT, "%.2f блока при допустимых %.2f, пинг %d мс",
                    dist, limit, p.getPing()));
            d.reachHits.clear();
        }
    }

    private static double distanceToBox(Vector eye, BoundingBox b) {
        double x = Math.max(b.getMinX(), Math.min(eye.getX(), b.getMaxX()));
        double y = Math.max(b.getMinY(), Math.min(eye.getY(), b.getMaxY()));
        double z = Math.max(b.getMinZ(), Math.min(eye.getZ(), b.getMaxZ()));
        return eye.distance(new Vector(x, y, z));
    }

    // ---------------------------------------------------------------- исключения

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent e) { exempt(e.getPlayer(), 2500); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) { if (e.getEntity() instanceof Player p) exempt(p, 1500); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) { exempt(e.getPlayer(), 2000); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) { exempt(e.getPlayer(), 3000); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent e) { exempt(e.getPlayer(), 3000); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent e) { exempt(e.getPlayer(), 3000); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent e) { exempt(e.getPlayer(), 2000); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { data.remove(e.getPlayer().getUniqueId()); }

    // ---------------------------------------------------------------- оповещения

    private void flag(Player p, GameSession s, String check, String title, String detail) {
        Data d = d(p);
        long now = System.currentTimeMillis();
        long cd = Math.max(0, cfg().getLong("alert-cooldown", 10)) * 1000;
        Long prev = d.lastAlert.get(check);
        if (prev != null && now - prev < cd) return;
        d.lastAlert.put(check, now);

        String where = s.mode.id + " #" + s.id;
        String plain = p.getName() + " — " + title + ": " + detail + " (" + where + ")";
        plugin.getLogger().warning("[Античит] " + plain);
        String mm = "<dark_red>[Античит] <red>" + Msg.esc(p.getName()) + " <gray>— <yellow>" + title + "<gray>: "
                + Msg.esc(detail) + " <dark_gray>(" + Msg.esc(where) + ")";
        for (Player a : Bukkit.getOnlinePlayers())
            if (a.hasPermission("mgp.admin") && !plugin.bots().isBot(a)) a.sendMessage(Msg.parse(mm));
        if (cfg().getBoolean("log", true)) log(plain);
    }

    private void log(String line) {
        File f = new File(plugin.getDataFolder(), "anticheat.log");
        try (PrintWriter w = new PrintWriter(new FileWriter(f, java.nio.charset.StandardCharsets.UTF_8, true))) {
            w.println("[" + LocalDateTime.now().format(TS) + "] " + line);
        } catch (IOException ex) {
            plugin.getLogger().warning("anticheat.log: " + ex.getMessage());
        }
    }
}
