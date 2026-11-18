package ru.mgp.bot;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import ru.mgp.MGPPlugin;
import ru.mgp.gun.GunService;
import ru.mgp.session.GameSession;
import ru.mgp.util.Keys;

import java.util.*;

/**
 * Тестовые боты (/mgp bot): настоящие игроки без клиента, чтобы проверять режимы одному.
 * Заходят в очередь и команды, считаются в min-players, ходят, бьют и стреляют по противникам, умирают и возрождаются.
 */
public final class BotService implements Listener {
    public enum Mode {
        /** Стоит на месте (мишень). */
        IDLE,
        /** Дерётся с ближайшим противником, без цели бродит. */
        FIGHT,
        /** Дерётся рядом, а так держится возле хозяина. */
        FOLLOW,
        /** Идёт к точке (проверить зоны и триггеры), по дороге отстреливается. */
        GOTO
    }

    public static final class Bot {
        public final UUID id;
        public final String name;
        public Mode mode;
        UUID owner;
        Location point;
        int deadTicks, attackCd, shootCd, stuckTicks, wanderTicks, thinkCd;
        float wanderYaw, strafe;
        UUID target;

        Bot(UUID id, String name, Mode mode, UUID owner) {
            this.id = id; this.name = name; this.mode = mode; this.owner = owner;
        }
    }

    private static final double SIGHT = 32, MELEE = 3.0, GUN_RANGE = 48;

    private final MGPPlugin plugin;
    private final Map<UUID, Bot> bots = new LinkedHashMap<>();
    private final Random random = new Random();
    private BukkitTask task;
    private boolean ticking;

    public BotService(MGPPlugin plugin) { this.plugin = plugin; }

    public boolean available() { return FakePlayers.init(); }
    public String error() { return FakePlayers.error(); }

    public boolean isBot(Player p) { return p != null && bots.containsKey(p.getUniqueId()); }
    public boolean isBot(UUID u) { return bots.containsKey(u); }
    public Collection<Bot> all() { return Collections.unmodifiableCollection(bots.values()); }
    public int count() { return bots.size(); }

    public List<Player> online() {
        List<Player> out = new ArrayList<>();
        for (Bot b : bots.values()) { Player p = Bukkit.getPlayer(b.id); if (p != null) out.add(p); }
        return out;
    }

    // ------------------------------------------------------------------ появление и уход

    /** Новый бот у точки at. null — не вышло (причина в логе / error()). */
    public Player spawn(Location at, Mode mode, UUID owner) {
        if (!available()) return null;
        String name = freeName();
        Bot b = new Bot(FakePlayers.offlineUuid(name), name, mode, owner);
        bots.put(b.id, b); // до входа: обработчики входа уже должны видеть, что это бот
        try {
            Player p = FakePlayers.spawn(name, at);
            startTask();
            return p;
        } catch (Throwable t) {
            bots.remove(b.id);
            Throwable c = t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Не удалось создать бота " + name, c);
            return null;
        }
    }

    private String freeName() {
        for (int i = 1; ; i++) {
            String n = "Bot" + i;
            if (Bukkit.getPlayerExact(n) == null && !bots.containsKey(FakePlayers.offlineUuid(n))) return n;
        }
    }

    public void remove(Player p) {
        Bot b = bots.get(p.getUniqueId());
        if (b == null) return;
        FakePlayers.remove(p);
        bots.remove(b.id);
    }

    public int removeAll() {
        int n = 0;
        for (Player p : online()) { remove(p); n++; }
        bots.clear();
        return n;
    }

    public void setMode(Player p, Mode mode, UUID owner, Location point) {
        Bot b = bots.get(p.getUniqueId());
        if (b == null) return;
        b.mode = mode;
        if (owner != null) b.owner = owner;
        b.point = point;
        b.target = null;
    }

    public void shutdown() {
        removeAll();
        if (task != null) task.cancel();
        task = null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        // бота выгнали (/kick) или он вышел сам — забываем
        if (!ticking) bots.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        Bot b = bots.get(e.getPlayer().getUniqueId());
        if (b != null) b.target = null;
    }

    // ------------------------------------------------------------------ тик

    private void startTask() {
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void tick() {
        if (bots.isEmpty()) return;
        ticking = true;
        try {
            for (Bot b : new ArrayList<>(bots.values())) {
                Player p = Bukkit.getPlayer(b.id);
                if (p == null) { bots.remove(b.id); continue; }
                try { tickBot(b, p); }
                catch (Throwable t) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING, "Бот " + b.name + " сломался, убираю", t);
                    try { FakePlayers.remove(p); } catch (Throwable ignored) {}
                    bots.remove(b.id);
                }
            }
        } finally { ticking = false; }
    }

    private void tickBot(Bot b, Player p) throws Exception {
        FakePlayers.settle(p); // после смерти и смены мира сервер ждёт подтверждений от клиента
        if (p.isDead()) {
            // настоящий клиент жмёт «Возродиться» — бот через секунду
            if (++b.deadTicks >= 20) { b.deadTicks = 0; p.spigot().respawn(); }
            FakePlayers.tick(p);
            return;
        }
        b.deadTicks = 0;
        if (b.attackCd > 0) b.attackCd--;
        if (b.shootCd > 0) b.shootCd--;

        Location from = p.getLocation();
        think(b, p);
        FakePlayers.tick(p);
        Location to = p.getLocation();
        if (to.getWorld() == from.getWorld() && (to.distanceSquared(from) > 1e-6 || to.getYaw() != from.getYaw() || to.getPitch() != from.getPitch()))
            fireMove(p, from, to);
    }

    /** У бота нет клиента, а зоны, триггеры и барьеры режимов слушают PlayerMoveEvent — шлём его сами. */
    private void fireMove(Player p, Location from, Location to) {
        PlayerMoveEvent e = new PlayerMoveEvent(p, from.clone(), to.clone());
        Bukkit.getPluginManager().callEvent(e);
        if (!p.isOnline() || p.isDead()) return;
        if (e.isCancelled()) p.teleport(from);
        else if (!sameSpot(e.getTo(), to)) p.teleport(e.getTo());
    }

    private static boolean sameSpot(Location a, Location b) {
        return a.getWorld() == b.getWorld() && a.distanceSquared(b) < 1e-6;
    }

    // ------------------------------------------------------------------ поведение

    private void think(Bot b, Player p) throws Exception {
        GameSession s = plugin.sessions().of(p);
        boolean inGame = s != null && s.isPlaying() && s.isAlive(p);
        GameMode gm = p.getGameMode();
        if (gm == GameMode.SPECTATOR || gm == GameMode.CREATIVE) { stop(p); return; }

        if (inGame && --b.thinkCd <= 0) {
            b.thinkCd = 20;
            useLogicItems(s, p);
        }

        Player target = b.mode == Mode.IDLE || !inGame ? null : findTarget(b, p, s);
        b.target = target == null ? null : target.getUniqueId();
        if (target != null) { fight(b, p, target); return; }

        switch (b.mode) {
            case IDLE -> stop(p);
            case FOLLOW -> {
                Player o = b.owner == null ? null : Bukkit.getPlayer(b.owner);
                if (o != null && o.getWorld() == p.getWorld() && o.getLocation().distanceSquared(p.getLocation()) > 9)
                    walkTo(b, p, o.getLocation(), o.getLocation().distanceSquared(p.getLocation()) > 64);
                else stop(p);
            }
            case GOTO -> {
                if (b.point != null && b.point.getWorld() == p.getWorld() && b.point.distanceSquared(p.getLocation()) > 1.5)
                    walkTo(b, p, b.point, true);
                else stop(p);
            }
            case FIGHT -> { if (inGame) wander(b, p); else stop(p); }
        }
    }

    /** Служебные предметы режима: например, «В бой» из лимбо штурма. */
    private void useLogicItems(GameSession s, Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            String tag = Keys.tag(inv.getItem(i));
            if (tag != null && tag.startsWith("logic:") && tag.contains("deploy")) {
                s.logic().onHotbar(p, tag.substring("logic:".length()));
                return;
            }
        }
    }

    private Player findTarget(Bot b, Player p, GameSession s) {
        String myTeam = s.logic().zoneTeam(p);
        Location me = p.getEyeLocation();
        Player best = null;
        double bestD = SIGHT * SIGHT;
        for (Player o : s.alivePlayers()) {
            if (o == p || o.getWorld() != p.getWorld() || o.isDead()) continue;
            GameMode gm = o.getGameMode();
            if (gm == GameMode.SPECTATOR || gm == GameMode.CREATIVE) continue;
            String t = s.logic().zoneTeam(o);
            if (myTeam != null && myTeam.equals(t)) continue;
            double d = o.getLocation().distanceSquared(p.getLocation());
            if (d >= bestD) continue;
            boolean current = o.getUniqueId().equals(b.target);
            if (!current && !p.hasLineOfSight(o)) continue;
            best = o;
            bestD = d;
        }
        return best;
    }

    private void fight(Bot b, Player p, Player t) throws Exception {
        double dist = p.getLocation().distance(t.getLocation());
        int gunSlot = bestSlot(p, true), meleeSlot = bestSlot(p, false);
        boolean gun = gunSlot >= 0 && (dist > MELEE + 1 || meleeSlot < 0);
        int slot = gun ? gunSlot : meleeSlot;
        if (slot >= 0 && p.getInventory().getHeldItemSlot() != slot) p.getInventory().setHeldItemSlot(slot);

        Location aim = t.getLocation().add(0, t.getHeight() * 0.6, 0);
        float[] yp = look(p.getEyeLocation(), aim);
        boolean sight = p.hasLineOfSight(t);

        if (gun) {
            // держим дистанцию и стреляем
            float fwd = dist > 14 || !sight ? 1f : dist < 6 ? -0.6f : 0f;
            if (--b.wanderTicks <= 0) { b.wanderTicks = 20 + random.nextInt(30); b.strafe = random.nextBoolean() ? 0.6f : -0.6f; }
            steer(b, p, fwd, b.strafe, yp[0], yp[1], fwd > 0);
            if (sight && dist <= GUN_RANGE && b.shootCd <= 0) {
                GunService g = plugin.guns();
                if (g != null && g.pullTrigger(p)) b.shootCd = 2;
            }
            return;
        }
        steer(b, p, dist > MELEE - 0.5 ? 1f : 0f, 0f, yp[0], yp[1], dist > 6);
        if (dist <= MELEE && sight && b.attackCd <= 0) {
            p.swingMainHand();
            p.attack(t);
            b.attackCd = 10 + random.nextInt(4);
        }
    }

    /** Лучший слот хотбара: оружие (gun=true) или ближний бой; -1 — нет. */
    private int bestSlot(Player p, boolean gun) {
        GunService g = plugin.guns();
        int best = -1, bestScore = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (it == null || it.getType().isAir() || Keys.tag(it) != null) continue;
            boolean isGun = g != null && g.spec(it) != null;
            if (isGun != gun) continue;
            int score = isGun ? 100 : meleeScore(it.getType());
            if (score > bestScore) { best = i; bestScore = score; }
        }
        return best;
    }

    private static int meleeScore(Material m) {
        String n = m.name();
        int tier = n.startsWith("NETHERITE") ? 6 : n.startsWith("DIAMOND") ? 5 : n.startsWith("IRON") ? 4
                : n.startsWith("STONE") || n.startsWith("COPPER") ? 3 : n.startsWith("GOLDEN") ? 2 : 1;
        if (n.endsWith("_SWORD")) return 20 + tier;
        if (n.endsWith("_AXE")) return 10 + tier;
        if (m == Material.MACE || m == Material.TRIDENT) return 25;
        return 0;
    }

    private void wander(Bot b, Player p) throws Exception {
        if (--b.wanderTicks <= 0) {
            b.wanderTicks = 30 + random.nextInt(60);
            b.wanderYaw = random.nextInt(4) == 0 ? Float.NaN : random.nextFloat() * 360f - 180f; // иногда стоит
        }
        if (Float.isNaN(b.wanderYaw)) { stop(p); return; }
        if (FakePlayers.blocked(p) && ++b.stuckTicks > 30) { b.wanderYaw += 90 + random.nextInt(180); b.stuckTicks = 0; }
        steer(b, p, 0.7f, 0f, b.wanderYaw, 0f, false);
    }

    private void walkTo(Bot b, Player p, Location to, boolean sprint) throws Exception {
        float[] yp = look(p.getEyeLocation(), to.clone().add(0, 1.6, 0));
        float yaw = yp[0];
        if (FakePlayers.blocked(p) && p.isOnGround()) b.stuckTicks++;
        else if (b.stuckTicks > 0) b.stuckTicks--;
        if (b.stuckTicks > 40) yaw += b.stuckTicks % 80 < 60 ? 70 : -70; // обойти стену
        if (b.stuckTicks > 120) b.stuckTicks = 0;
        steer(b, p, 1f, 0f, yaw, 0f, sprint);
    }

    private void steer(Bot b, Player p, float forward, float strafe, float yaw, float pitch, boolean sprint) throws Exception {
        boolean jump = p.isInWater() || p.isInLava() || (forward != 0 && FakePlayers.blocked(p) && p.isOnGround());
        if (p.isSprinting() != (sprint && forward > 0)) p.setSprinting(sprint && forward > 0);
        FakePlayers.steer(p, forward, strafe, jump, yaw, pitch);
    }

    private void stop(Player p) throws Exception {
        if (p.isSprinting()) p.setSprinting(false);
        Location l = p.getLocation();
        FakePlayers.steer(p, 0f, 0f, p.isInWater() || p.isInLava(), l.getYaw(), l.getPitch());
    }

    private static float[] look(Location eye, Location at) {
        Vector d = at.toVector().subtract(eye.toVector());
        double h = Math.sqrt(d.getX() * d.getX() + d.getZ() * d.getZ());
        float yaw = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.getY(), h));
        return new float[]{yaw, pitch};
    }
}
