package ru.mgp.command;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import ru.mgp.config.MapConfig;
import ru.mgp.util.Pos;
import ru.mgp.util.Region;

import java.util.*;

/**
 * Настройка участков Билд баттла: автопоиск стен между участками и подсветка участков/стен частицами.
 */
public final class PlotSetup {
    private PlotSetup() {}

    /** Найденные стены и пояснения для игрока (какие пары участков пропущены и почему). */
    public record AutoResult(List<Region> walls, List<String> notes) {}

    /**
     * Стены между соседними участками: зазор между ними (1..maxGap блоков) по всей общей длине,
     * плюс угловые столбы, где сходятся четыре участка. Слои пола (где оба участка почти сплошные)
     * не трогаем, сверху обрезаем пустой воздух.
     */
    public static AutoResult autoWalls(World w, List<MapConfig.Plot> plots, int maxGap) {
        List<Region> walls = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (int i = 0; i < plots.size(); i++) {
            for (int j = i + 1; j < plots.size(); j++) {
                Region a = plots.get(i).region(), b = plots.get(j).region();
                int[] gx = gap(a.minX(), a.maxX(), b.minX(), b.maxX());
                int[] gz = gap(a.minZ(), a.maxZ(), b.minZ(), b.maxZ());
                String pair = "участки " + (i + 1) + " и " + (j + 1);
                int x1, x2, z1, z2;
                if (gx == null && gz == null) { notes.add(pair + " пересекаются — проверь выделение"); continue; }
                if (gx != null && gz != null) { // по диагонали: угловой столб между четырьмя участками
                    if (!within(gx, maxGap) || !within(gz, maxGap)) continue;
                    x1 = gx[0]; x2 = gx[1]; z1 = gz[0]; z2 = gz[1];
                } else if (gx != null) {
                    if (gx[1] < gx[0]) { notes.add(pair + " вплотную — стену между ними выдели сам: /mgp setup wall"); continue; }
                    if (!within(gx, maxGap)) continue;
                    x1 = gx[0]; x2 = gx[1]; z1 = Math.max(a.minZ(), b.minZ()); z2 = Math.min(a.maxZ(), b.maxZ());
                } else {
                    if (gz[1] < gz[0]) { notes.add(pair + " вплотную — стену между ними выдели сам: /mgp setup wall"); continue; }
                    if (!within(gz, maxGap)) continue;
                    z1 = gz[0]; z2 = gz[1]; x1 = Math.max(a.minX(), b.minX()); x2 = Math.min(a.maxX(), b.maxX());
                }
                int y1 = Math.min(a.minY(), b.minY()), y2 = Math.max(a.maxY(), b.maxY());
                // пол участков (почти сплошной слой снизу) оставляем, иначе между постройками будет канава
                int floor = y1 - 1;
                for (int y = y1; y <= y2 && solidLayer(w, a, y) && solidLayer(w, b, y); y++) floor = y;
                y1 = floor + 1;
                // сверху обрезаем воздух
                int top = y1 - 1;
                for (int y = y2; y >= y1 && top < y1; y--) if (anyBlock(w, x1, x2, y, z1, z2)) top = y;
                if (top < y1) {
                    if (!(gx != null && gz != null)) notes.add(pair + ": в зазоре между ними нет блоков — стены нет");
                    continue;
                }
                Region wall = new Region(x1, y1, z1, x2, top, z2);
                if (!walls.contains(wall)) walls.add(wall); // угол между четырьмя участками находят две диагональные пары
            }
        }
        return new AutoResult(walls, notes);
    }

    /** Зазор между отрезками [a1,a2] и [b1,b2]: {от, до} (до < от — вплотную) или null, если отрезки пересекаются. */
    private static int[] gap(int a1, int a2, int b1, int b2) {
        if (a2 < b1) return new int[]{a2 + 1, b1 - 1};
        if (b2 < a1) return new int[]{b2 + 1, a1 - 1};
        return null;
    }

    private static boolean within(int[] g, int maxGap) { return g[1] >= g[0] && g[1] - g[0] + 1 <= maxGap; }

    /** Слой участка на высоте y почти сплошной (≥90% твёрдых блоков) — это пол. */
    private static boolean solidLayer(World w, Region r, int y) {
        long total = 0, solid = 0;
        int step = Math.max(1, (int) Math.sqrt((double) (r.maxX() - r.minX() + 1) * (r.maxZ() - r.minZ() + 1) / 2500.0));
        for (int x = r.minX(); x <= r.maxX(); x += step)
            for (int z = r.minZ(); z <= r.maxZ(); z += step) {
                total++;
                if (w.getBlockAt(x, y, z).getType().isSolid()) solid++;
            }
        return total > 0 && solid * 10 >= total * 9;
    }

    private static boolean anyBlock(World w, int x1, int x2, int y, int z1, int z2) {
        for (int x = x1; x <= x2; x++)
            for (int z = z1; z <= z2; z++)
                if (!w.getBlockAt(x, y, z).getType().isAir()) return true;
        return false;
    }

    // ------------------------------------------------------------------ подсветка


    /** Рамки на seconds секунд: участки — зелёные, стены — красные, центр арены — белый столбик,
     *  play-зона — голубая, зоны команд — оранжевая, синяя, ... (в порядке team-zones), части крепости — фиолетовые,
     *  цели на разрушение — серые, спавны участков — жёлтые кубики. Над крепостью, участками и целями — подписи
     *  с номерами. Всё видит только этот игрок; рамки сплошные на любом расстоянии (см. {@link Outline}). */
    public static void show(Plugin plugin, Player p, World w, MapConfig m, int seconds) {
        stop(p);
        if (seconds <= 0) return;
        List<org.bukkit.entity.Entity> labels = new ArrayList<>();
        for (int i = 0; i < m.fortress.size(); i++) labels.add(label(plugin, p, w, m.fortress.get(i), "<light_purple><bold>Крепость " + (i + 1)));
        for (int i = 0; i < m.plots.size(); i++) labels.add(label(plugin, p, w, m.plots.get(i).region(), "<green><bold>Участок " + (i + 1)));
        for (MapConfig.Objective o : m.objectives) labels.add(label(plugin, p, w, o.region(), "<gray>Цель </gray>" + o.name()));
        List<Map<?, ?>> stages = m.yaml.getMapList("stages");
        for (int i = 0; i < stages.size(); i++) {
            Map<String, List<Region>> z = ru.mgp.logic.AssaultLogic.zones(stages.get(i).get("zones"));
            for (var e : z.entrySet())
                for (int k = 0; k < e.getValue().size(); k++)
                    labels.add(label(plugin, p, w, e.getValue().get(k), "<yellow><bold>Стадия " + (i + 1) + "</bold> <white>" + e.getKey()
                            + (e.getValue().size() > 1 ? " <gray>(" + (k + 1) + "/" + e.getValue().size() + ")" : "")));
        }
        LABELS.put(p.getUniqueId(), labels);
        Outline o = new Outline(plugin, p, w);
        for (MapConfig.Plot pl : m.plots) {
            o.box(pl.region(), Material.LIME_CONCRETE, Color.LIME);
            if (pl.spawn() != null) marker(o, pl.spawn(), 0.4, Material.YELLOW_CONCRETE, Color.YELLOW);
        }
        for (Region r : m.walls) o.box(r, Material.RED_CONCRETE, Color.RED);
        for (Region r : m.playZone) o.box(r, Material.LIGHT_BLUE_CONCRETE, Color.AQUA);
        int ti = 0;
        for (List<Region> l : m.teamZones.values()) {
            for (Region r : l) o.box(r, TEAM_BLOCK[ti % TEAM_BLOCK.length], TEAM_GLOW[ti % TEAM_GLOW.length]);
            ti++;
        }
        for (int i = 0; i < stages.size(); i++) {
            Map<String, List<Region>> z = ru.mgp.logic.AssaultLogic.zones(stages.get(i).get("zones"));
            for (List<Region> l : z.values())
                for (Region r : l) o.box(r, STAGE_BLOCK[i % STAGE_BLOCK.length], STAGE_GLOW[i % STAGE_GLOW.length]);
        }
        for (Region r : m.fortress) o.box(r, Material.PURPLE_CONCRETE, Color.fromRGB(0xc050ff));
        for (MapConfig.Objective ob : m.objectives) o.box(ob.region(), Material.LIGHT_GRAY_CONCRETE, Color.SILVER);
        Pos arena = m.arena != null ? m.arena : m.spectator;
        if (arena != null) o.cube(arena.x() - 0.15, arena.y(), arena.z() - 0.15, arena.x() + 0.15, arena.y() + 3, arena.z() + 0.15, Material.WHITE_CONCRETE, Color.fromRGB(0xFEFEFE));
        OUTLINES.put(p.getUniqueId(), o);
        int[] left = {seconds * 4};
        BukkitTask t = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (left[0]-- <= 0 || !p.isOnline() || !p.getWorld().equals(w)) { stop(p); return; }
            o.refresh();
        }, 0L, 5L);
        SHOWING.put(p.getUniqueId(), t);
    }

    private static void marker(Outline o, Pos at, double size, Material m, Color c) {
        double h = size / 2;
        o.cube(at.x() - h, at.y(), at.z() - h, at.x() + h, at.y() + size, at.z() + h, m, c);
    }

    private static final Map<UUID, BukkitTask> SHOWING = new HashMap<>();
    private static final Map<UUID, Outline> OUTLINES = new HashMap<>();
    private static final Material[] TEAM_BLOCK = {Material.ORANGE_CONCRETE, Material.BLUE_CONCRETE, Material.MAGENTA_CONCRETE, Material.GREEN_CONCRETE};
    private static final Material[] STAGE_BLOCK = {Material.YELLOW_CONCRETE, Material.CYAN_CONCRETE, Material.PINK_CONCRETE, Material.WHITE_CONCRETE};
    private static final Color[] STAGE_GLOW = {Color.YELLOW, Color.TEAL, Color.fromRGB(0xff80c0), Color.WHITE};
    private static final Color[] TEAM_GLOW = {Color.ORANGE, Color.BLUE, Color.FUCHSIA, Color.GREEN};

    private static final Map<UUID, List<org.bukkit.entity.Entity>> LABELS = new HashMap<>();

    /** Убрать подсветку и подписи игрока. */
    public static void stop(Player p) {
        BukkitTask old = SHOWING.remove(p.getUniqueId());
        if (old != null) old.cancel();
        Outline ol = OUTLINES.remove(p.getUniqueId());
        if (ol != null) ol.hide();
        List<org.bukkit.entity.Entity> l = LABELS.remove(p.getUniqueId());
        if (l != null) for (org.bukkit.entity.Entity e : l) if (e.isValid()) e.remove();
    }

    /** Подпись над областью: TextDisplay, который видит только этот игрок. */
    private static org.bukkit.entity.Entity label(Plugin plugin, Player p, World w, Region r, String text) {
        org.bukkit.Location at = new org.bukkit.Location(w, (r.minX() + r.maxX() + 1) / 2.0, r.maxY() + 1.6, (r.minZ() + r.maxZ() + 1) / 2.0);
        org.bukkit.entity.TextDisplay d = w.spawn(at, org.bukkit.entity.TextDisplay.class, x -> {
            x.setPersistent(false);
            x.setVisibleByDefault(false);
            x.text(ru.mgp.util.Msg.parse(text));
            x.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            x.setSeeThrough(true);
            x.setShadowed(true);
            x.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.Quaternionf(),
                    new org.joml.Vector3f(1.8f, 1.8f, 1.8f), new org.joml.Quaternionf()));
        });
        p.showEntity(plugin, d);
        return d;
    }
}
