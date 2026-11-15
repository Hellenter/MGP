package ru.mgp.session;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import ru.mgp.config.MapConfig;
import ru.mgp.config.MapConfig.Script;
import ru.mgp.util.Msg;

import java.util.*;

/**
 * Скриптовые объекты карты (maps/<карта>.yml → scripts) вместо командных блоков.
 * Работают только для живых игроков во время игры. Действия — те же, что у триггеров (ActionRunner).
 *
 *   hold   — подойти к блоку и держать Shift hold сек.: частицы вокруг блока, подсказка над хотбаром,
 *            шкала прогресса (боссбар) с процентами и временем; отпустил или отошёл — прогресс сброшен.
 *   button — ПКМ по блоку (кнопка, рычаг, люк, любой блок).
 *   plate  — наступить на блок (нажимная плита и т.п.).
 *   zone   — войти в радиус вокруг блока.
 */
public final class MapScripts {
    /** Шаг обновления в тиках. */
    public static final int STEP = 2;

    private final GameSession s;
    private final Map<UUID, String> holdScript = new HashMap<>();
    private final Map<UUID, Integer> holdTicks = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, Set<String>> inZone = new HashMap<>();
    private final Map<String, Long> cooldownUntil = new HashMap<>();
    private final Set<String> fired = new HashSet<>();
    private final Map<String, Set<UUID>> firedPer = new HashMap<>();
    private int ticks;

    MapScripts(GameSession s) { this.s = s; }

    private List<Script> scripts() {
        MapConfig m = s.map();
        return m == null ? List.of() : m.scripts;
    }

    private static Location center(World w, Script sc) {
        return new Location(w, sc.block().bx() + 0.5, sc.block().by() + 0.5, sc.block().bz() + 0.5);
    }

    private static boolean near(Player p, Location c, double radius) {
        Location l = p.getLocation();
        if (l.getWorld() != c.getWorld()) return false;
        double dx = l.getX() - c.getX(), dz = l.getZ() - c.getZ();
        double dy = l.getY() - (c.getY() - 0.5); // от ног до низа блока
        return dx * dx + dz * dz <= radius * radius && dy > -1.5 && dy < 2.0;
    }

    /** Каждые STEP тиков, пока идёт игра. */
    public void tick() {
        List<Script> list = scripts();
        World w = s.gameWorld();
        if (list.isEmpty() || w == null || !s.isPlaying()) return;
        ticks += STEP;
        boolean hintFrame = ticks % 20 == 0;

        // частицы вокруг удерживаемых объектов: подсказка, что тут что-то есть
        if (ticks % 6 == 0) for (Script sc : list) {
            if (sc.type() != Script.Type.HOLD || sc.particle() == null || !available(sc, null)) continue;
            Location c = center(w, sc);
            double a = ticks / 6.0 * 0.7;
            for (int i = 0; i < 3; i++) {
                double ang = a + i * Math.PI * 2 / 3;
                particle(w, sc.particle(), c.clone().add(Math.cos(ang) * 0.7, -0.3, Math.sin(ang) * 0.7), 1, 0.02);
            }
        }

        for (Player p : s.alivePlayers()) {
            UUID u = p.getUniqueId();
            Script holdHere = null;
            Set<String> zonesNow = new HashSet<>();
            for (Script sc : list) {
                switch (sc.type()) {
                    case HOLD -> { if (holdHere == null && near(p, center(w, sc), sc.radius())) holdHere = sc; }
                    case ZONE -> { if (near(p, center(w, sc), sc.radius())) zonesNow.add(sc.id()); }
                    default -> {}
                }
            }
            // вход в зоны
            Set<String> before = inZone.getOrDefault(u, Set.of());
            for (Script sc : list) if (sc.type() == Script.Type.ZONE && zonesNow.contains(sc.id()) && !before.contains(sc.id())) fire(sc, p);
            if (zonesNow.isEmpty()) inZone.remove(u); else inZone.put(u, zonesNow);

            // удержание Shift
            if (holdHere == null || !available(holdHere, p)) {
                if (holdHere != null && hintFrame) p.sendActionBar(Msg.parse("<gray>Перезарядка: " + cooldownLeft(holdHere, p) + " сек."));
                cancelHold(p, holdTicks.getOrDefault(u, 0) > 6);
                continue;
            }
            if (!holdHere.id().equals(holdScript.get(u))) cancelHold(p, false);
            if (!p.isSneaking()) {
                if (holdTicks.containsKey(u)) cancelHold(p, true);
                if (hintFrame && holdHere.hint() != null && !holdHere.hint().isBlank()) p.sendActionBar(Msg.parse(holdHere.hint()));
                continue;
            }
            holdScript.put(u, holdHere.id());
            int t = holdTicks.merge(u, STEP, Integer::sum);
            int need = Math.max(STEP, (int) Math.round(holdHere.hold() * 20));
            float progress = Math.min(1f, t / (float) need);
            showBar(p, holdHere, progress, Math.max(0, need - t) / 20.0);
            spiral(w, p, holdHere, progress);
            if (t % 8 == 0) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.4f, 0.6f + progress * 1.2f);
            if (t >= need) {
                cancelHold(p, false);
                p.sendActionBar(Component.empty());
                fire(holdHere, p);
            }
        }
    }

    private void showBar(Player p, Script sc, float progress, double secLeft) {
        String title = (sc.progress() == null ? "<aqua>Активация" : sc.progress())
                + " <white>" + Math.round(progress * 100) + "% <gray>(" + String.format(Locale.ROOT, "%.1f", secLeft) + " с)";
        BossBar bar = bars.get(p.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(Msg.parse(title), progress, BossBar.Color.BLUE, BossBar.Overlay.NOTCHED_10);
            bars.put(p.getUniqueId(), bar);
            p.showBossBar(bar);
        } else {
            bar.name(Msg.parse(title));
            bar.progress(progress);
        }
        int filled = Math.round(progress * 20);
        p.sendActionBar(Msg.parse("<aqua>" + "|".repeat(filled) + "<dark_gray>" + "|".repeat(20 - filled) + " <gray>— держи <gold>SHIFT"));
    }

    private void spiral(World w, Player p, Script sc, float progress) {
        String pt = sc.particle() == null ? "PORTAL" : sc.particle();
        Location l = p.getLocation();
        double a = ticks * 0.5;
        for (int i = 0; i < 2; i++) {
            double ang = a + i * Math.PI;
            particle(w, pt, l.clone().add(Math.cos(ang) * 0.8, 0.1 + progress * 1.9, Math.sin(ang) * 0.8), 1, 0);
        }
        particle(w, pt, center(w, sc), 4, 0.15);
    }

    private void cancelHold(Player p, boolean notify) {
        UUID u = p.getUniqueId();
        holdScript.remove(u);
        Integer had = holdTicks.remove(u);
        BossBar bar = bars.remove(u);
        if (bar != null) p.hideBossBar(bar);
        if (notify && had != null && had > 0) p.sendActionBar(Msg.parse("<red>Отменено"));
    }

    /** Клики по блокам: кнопки и плиты. true — событие обработано скриптом. */
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || scripts().isEmpty()) return;
        Script.Type type = e.getAction() == Action.RIGHT_CLICK_BLOCK ? Script.Type.BUTTON
                : e.getAction() == Action.PHYSICAL ? Script.Type.PLATE : null;
        if (type == null) return;
        if (type == Script.Type.BUTTON && e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        for (Script sc : scripts()) {
            if (sc.type() != type || sc.block().bx() != b.getX() || sc.block().by() != b.getY() || sc.block().bz() != b.getZ()) continue;
            if (sc.cancelUse()) e.setCancelled(true);
            if (!available(sc, e.getPlayer())) {
                if (type == Script.Type.BUTTON) e.getPlayer().sendActionBar(Msg.parse("<gray>Пока недоступно (" + cooldownLeft(sc, e.getPlayer()) + " сек.)"));
                return;
            }
            fire(sc, e.getPlayer());
            return;
        }
    }

    private String key(Script sc, Player p) { return sc.id() + ":" + (p == null ? "" : p.getUniqueId()); }

    /** Можно ли сейчас сработать (режим срабатывания и перезарядка). p == null — для кого-нибудь вообще. */
    private boolean available(Script sc, Player p) {
        switch (sc.mode()) {
            case ONCE -> { if (fired.contains(sc.id())) return false; }
            case PER_PLAYER -> { if (p != null && firedPer.getOrDefault(sc.id(), Set.of()).contains(p.getUniqueId())) return false; }
            default -> {}
        }
        return p == null || cooldownLeft(sc, p) == 0;
    }

    private long cooldownLeft(Script sc, Player p) {
        Long until = cooldownUntil.get(key(sc, p));
        return until == null ? 0 : Math.max(0, (until - System.currentTimeMillis() + 999) / 1000);
    }

    private void fire(Script sc, Player p) {
        if (!available(sc, p)) return;
        fired.add(sc.id());
        firedPer.computeIfAbsent(sc.id(), k -> new HashSet<>()).add(p.getUniqueId());
        if (sc.cooldown() > 0) cooldownUntil.put(key(sc, p), System.currentTimeMillis() + sc.cooldown() * 1000L);
        ActionRunner.run(s, sc.actions(), p);
    }

    /** Убрать шкалы прогресса (конец игры, выход игрока). */
    public void clear(Player p) {
        cancelHold(p, false);
        inZone.remove(p.getUniqueId());
    }

    public void clearAll() {
        for (UUID u : new ArrayList<>(bars.keySet())) {
            Player p = org.bukkit.Bukkit.getPlayer(u);
            BossBar bar = bars.remove(u);
            if (p != null && bar != null) p.hideBossBar(bar);
        }
        holdScript.clear();
        holdTicks.clear();
        inZone.clear();
    }

    static void particle(World w, String name, Location at, int count, double spread) {
        try {
            Particle pt = Particle.valueOf(name.trim().toUpperCase(Locale.ROOT));
            if (pt.getDataType() == Void.class) w.spawnParticle(pt, at, count, spread, spread, spread, 0);
        } catch (IllegalArgumentException ignored) {
        }
    }
}
