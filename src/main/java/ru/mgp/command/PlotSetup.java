package ru.mgp.command;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Particle;
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

    private static final Map<UUID, BukkitTask> SHOWING = new HashMap<>();
    private static final Particle.DustOptions[] TEAM = {new Particle.DustOptions(Color.ORANGE, 1.5f),
            new Particle.DustOptions(Color.BLUE, 1.5f), new Particle.DustOptions(Color.FUCHSIA, 1.5f), new Particle.DustOptions(Color.OLIVE, 1.5f)};

    /** Рамки частицами на seconds секунд: участки — зелёные, стены — красные, центр арены — белый столб,
     *  play-зона — голубая, зоны команд — оранжевая, синяя, ... (в порядке team-zones). */
    public static void show(Plugin plugin, Player p, World w, MapConfig m, int seconds) {
        BukkitTask old = SHOWING.remove(p.getUniqueId());
        if (old != null) old.cancel();
        Particle.DustOptions green = new Particle.DustOptions(Color.LIME, 1.2f), red = new Particle.DustOptions(Color.RED, 1.2f),
                white = new Particle.DustOptions(Color.WHITE, 1.5f), yellow = new Particle.DustOptions(Color.YELLOW, 1.5f),
                cyan = new Particle.DustOptions(Color.AQUA, 1.5f);
        int[] left = {seconds * 2};
        BukkitTask t = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (left[0]-- <= 0 || !p.isOnline() || !p.getWorld().equals(w)) {
                BukkitTask me = SHOWING.remove(p.getUniqueId());
                if (me != null) me.cancel();
                return;
            }
            for (MapConfig.Plot pl : m.plots) {
                edges(p, pl.region(), green);
                if (pl.spawn() != null) column(p, pl.spawn(), yellow, 2);
            }
            for (Region r : m.walls) edges(p, r, red);
            for (Region r : m.playZone) edges(p, r, cyan);
            int ti = 0;
            for (List<Region> l : m.teamZones.values()) { for (Region r : l) edges(p, r, TEAM[ti % TEAM.length]); ti++; }
            Pos arena = m.arena != null ? m.arena : m.spectator;
            if (arena != null) column(p, arena, white, 4);
        }, 0L, 10L);
        SHOWING.put(p.getUniqueId(), t);
    }

    private static void edges(Player p, Region r, Particle.DustOptions d) {
        double x1 = r.minX(), y1 = r.minY(), z1 = r.minZ(), x2 = r.maxX() + 1, y2 = r.maxY() + 1, z2 = r.maxZ() + 1;
        double[][] lines = {
                {x1, y1, z1, x2, y1, z1}, {x1, y1, z2, x2, y1, z2}, {x1, y2, z1, x2, y2, z1}, {x1, y2, z2, x2, y2, z2},
                {x1, y1, z1, x1, y1, z2}, {x2, y1, z1, x2, y1, z2}, {x1, y2, z1, x1, y2, z2}, {x2, y2, z1, x2, y2, z2},
                {x1, y1, z1, x1, y2, z1}, {x2, y1, z1, x2, y2, z1}, {x1, y1, z2, x1, y2, z2}, {x2, y1, z2, x2, y2, z2}};
        for (double[] l : lines) {
            double len = Math.max(Math.abs(l[3] - l[0]), Math.max(Math.abs(l[4] - l[1]), Math.abs(l[5] - l[2])));
            int n = (int) Math.min(200, Math.max(1, len));
            for (int i = 0; i <= n; i++) {
                double k = (double) i / n;
                p.spawnParticle(Particle.DUST, l[0] + (l[3] - l[0]) * k, l[1] + (l[4] - l[1]) * k, l[2] + (l[5] - l[2]) * k, 1, 0, 0, 0, 0, d);
            }
        }
    }

    private static void column(Player p, Pos at, Particle.DustOptions d, int height) {
        for (double y = 0; y <= height; y += 0.5) p.spawnParticle(Particle.DUST, at.x(), at.y() + y, at.z(), 1, 0, 0, 0, 0, d);
    }
}
