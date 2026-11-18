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
import ru.mgp.config.MapConfig.Trigger;
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
 *   area   — войти в область (параллелепипед min..max из выделения).
 * У zone и area: on: enter (при входе) | inside (пока внутри, раз в cooldown сек.) | leave (при выходе);
 * players: N — сработать, только когда внутри одновременно не меньше N игроков (действия — для всех, кто внутри).
 */
public final class MapScripts {
    /** Шаг обновления в тиках. */
    public static final int STEP = 2;

    private final GameSession s;
    private final Map<UUID, String> holdScript = new HashMap<>();
    private final Map<UUID, Integer> holdTicks = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    /** Кто был внутри зоны/области на прошлом шаге (по id скрипта). */
    private final Map<String, Set<UUID>> inside = new HashMap<>();
    /** Зоны, в которых на прошлом шаге было достаточно игроков (players). */
    private final Set<String> enough = new HashSet<>();
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

    /** Игрок внутри зоны (радиус) или области (параллелепипед). Для области считается блок ног. */
    static boolean within(Player p, World w, Script sc) {
        if (sc.type() == Script.Type.AREA) return p.getWorld() == w && sc.area().contains(p.getLocation());
        return near(p, center(w, sc), sc.radius());
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

        // контур зон и областей частицами (если у скрипта задан particle)
        if (ticks % 10 == 0) for (Script sc : list) {
            if (sc.particle() == null || sc.particle().equalsIgnoreCase("none") || !available(sc, null)) continue;
            if (sc.type() == Script.Type.AREA) outline(w, sc);
            else if (sc.type() == Script.Type.ZONE) ring(w, sc);
        }

        List<Player> alive = s.alivePlayers();
        for (Script sc : list) if (sc.type() == Script.Type.ZONE || sc.type() == Script.Type.AREA) zoneTick(sc, w, alive);

        for (Player p : alive) {
            UUID u = p.getUniqueId();
            Script holdHere = null;
            for (Script sc : list) {
                if (sc.type() == Script.Type.HOLD && near(p, center(w, sc), sc.radius())) { holdHere = sc; break; }
            }

            // удержание Shift
            if (holdHere == null || !available(holdHere, p)) {
                if (holdHere != null && hintFrame) p.sendActionBar(Msg.parse(unavailableText(holdHere, p)));
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

    /** Зона или область: кто вошёл, кто внутри, кто вышел — и сработать по on / players. */
    private void zoneTick(Script sc, World w, List<Player> alive) {
        List<Player> now = new ArrayList<>();
        for (Player p : alive) if (within(p, w, sc)) now.add(p);
        Set<UUID> before = inside.getOrDefault(sc.id(), Set.of());
        boolean ok = now.size() >= sc.players(), wasOk = enough.contains(sc.id());
        switch (sc.on()) {
            case ENTER -> {
                if (ok) {
                    // набралось нужное число — для всех внутри; дальше — для каждого вошедшего
                    List<Player> who = new ArrayList<>();
                    for (Player p : now) if (!wasOk || !before.contains(p.getUniqueId())) who.add(p);
                    fire(sc, who);
                }
            }
            case INSIDE -> { if (ok) fire(sc, now); }
            case LEAVE -> {
                List<Player> who = new ArrayList<>();
                Set<UUID> nowIds = new HashSet<>();
                for (Player p : now) nowIds.add(p.getUniqueId());
                for (Player p : alive) if (before.contains(p.getUniqueId()) && !nowIds.contains(p.getUniqueId())) who.add(p);
                fire(sc, who);
            }
        }
        if (now.isEmpty()) inside.remove(sc.id());
        else {
            Set<UUID> ids = new HashSet<>();
            for (Player p : now) ids.add(p.getUniqueId());
            inside.put(sc.id(), ids);
        }
        if (ok) enough.add(sc.id()); else enough.remove(sc.id());
    }

    /** Контур низа области частицами (по периметру, не больше ~120 точек). */
    private void outline(World w, Script sc) {
        var r = sc.area();
        double y = r.minY() + 0.1;
        double x0 = r.minX(), x1 = r.maxX() + 1, z0 = r.minZ(), z1 = r.maxZ() + 1;
        double per = 2 * ((x1 - x0) + (z1 - z0));
        double step = Math.max(0.5, per / 120);
        for (double d = 0; d < per; d += step) {
            double x, z;
            if (d < x1 - x0) { x = x0 + d; z = z0; }
            else if (d < (x1 - x0) + (z1 - z0)) { x = x1; z = z0 + d - (x1 - x0); }
            else if (d < 2 * (x1 - x0) + (z1 - z0)) { x = x1 - (d - (x1 - x0) - (z1 - z0)); z = z1; }
            else { x = x0; z = z1 - (d - 2 * (x1 - x0) - (z1 - z0)); }
            particle(w, sc.particle(), new Location(w, x, y, z), 1, 0);
        }
    }

    private void ring(World w, Script sc) {
        Location c = center(w, sc).add(0, -0.4, 0);
        int n = (int) Math.max(12, Math.min(64, sc.radius() * 8));
        for (int i = 0; i < n; i++) {
            double ang = i * Math.PI * 2 / n;
            particle(w, sc.particle(), c.clone().add(Math.cos(ang) * sc.radius(), 0, Math.sin(ang) * sc.radius()), 1, 0);
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
                if (type == Script.Type.BUTTON) e.getPlayer().sendActionBar(Msg.parse(unavailableText(sc, e.getPlayer())));
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

    /** Почему нельзя: перезарядка или уже использовано (режимы once / per-player). */
    private String unavailableText(Script sc, Player p) {
        long left = cooldownLeft(sc, p);
        return left > 0 ? "<gray>Перезарядка: <white>" + left + " сек." : "<gray>Уже использовано";
    }

    private long cooldownLeft(Script sc, Player p) {
        Long until = cooldownUntil.get(key(sc, p));
        return until == null ? 0 : Math.max(0, (until - System.currentTimeMillis() + 999) / 1000);
    }

    private void fire(Script sc, Player p) { fire(sc, List.of(p)); }

    /** Сработать для нескольких игроков сразу (зона набрала players): «один раз за игру» — один раз для всей группы. */
    private void fire(Script sc, List<Player> players) {
        if (players.isEmpty() || sc.mode() == Trigger.Mode.ONCE && fired.contains(sc.id())) return;
        List<Player> go = new ArrayList<>();
        for (Player p : players) if (available(sc, p)) go.add(p);
        if (go.isEmpty()) return;
        fired.add(sc.id());
        for (Player p : go) {
            firedPer.computeIfAbsent(sc.id(), k -> new HashSet<>()).add(p.getUniqueId());
            if (sc.cooldown() > 0) cooldownUntil.put(key(sc, p), System.currentTimeMillis() + sc.cooldown() * 1000L);
        }
        for (Player p : go) ActionRunner.run(s, sc.actions(), p);
    }

    /** Убрать шкалы прогресса (конец игры, выход игрока). */
    public void clear(Player p) {
        cancelHold(p, false);
        for (Set<UUID> in : inside.values()) in.remove(p.getUniqueId());
    }

    public void clearAll() {
        for (UUID u : new ArrayList<>(bars.keySet())) {
            Player p = org.bukkit.Bukkit.getPlayer(u);
            BossBar bar = bars.remove(u);
            if (p != null && bar != null) p.hideBossBar(bar);
        }
        holdScript.clear();
        holdTicks.clear();
        inside.clear();
        enough.clear();
    }

    static void particle(World w, String name, Location at, int count, double spread) {
        try {
            Particle pt = Particle.valueOf(name.trim().toUpperCase(Locale.ROOT));
            if (pt.getDataType() == Void.class) w.spawnParticle(pt, at, count, spread, spread, spread, 0);
        } catch (IllegalArgumentException ignored) {
        }
    }
}
