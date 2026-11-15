package ru.mgp.command;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import ru.mgp.util.Pos;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Показывает игроку границы его выделения /mgp setup pos1 / pos2 частицами (видит только он сам).
 * Рисуются только участки рамки рядом с игроком, поэтому большая область не создаёт лишней нагрузки.
 */
final class SelectionDisplay {
    private static final double RADIUS = 48, STEP = 0.5;
    private static final int MAX_PER_DRAW = 900;
    private static final Particle.DustOptions EDGE = new Particle.DustOptions(Color.fromRGB(80, 255, 80), 0.9f);
    private static final Particle.DustOptions P1 = new Particle.DustOptions(Color.fromRGB(255, 70, 70), 1.6f);
    private static final Particle.DustOptions P2 = new Particle.DustOptions(Color.fromRGB(70, 150, 255), 1.6f);

    private final Map<UUID, Pos> pos1, pos2;
    private final Map<UUID, String> world;

    SelectionDisplay(Map<UUID, Pos> pos1, Map<UUID, Pos> pos2, Map<UUID, String> world) {
        this.pos1 = pos1;
        this.pos2 = pos2;
        this.world = world;
    }

    /** Вызывается таймером раз в 10 тиков. */
    void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            Pos a = pos1.get(id), b = pos2.get(id);
            if (a == null && b == null) continue;
            if (!p.getWorld().getName().equals(world.get(id))) continue;
            if (a == null) a = b;
            if (b == null) b = a;
            draw(p, a, b);
        }
    }

    private void draw(Player p, Pos a, Pos b) {
        // рамка по внешним граням блоков: от min до max+1
        double x0 = Math.min(a.bx(), b.bx()), y0 = Math.min(a.by(), b.by()), z0 = Math.min(a.bz(), b.bz());
        double x1 = Math.max(a.bx(), b.bx()) + 1, y1 = Math.max(a.by(), b.by()) + 1, z1 = Math.max(a.bz(), b.bz()) + 1;
        Location l = p.getLocation();
        double px = l.getX(), py = l.getY(), pz = l.getZ();
        int[] budget = {MAX_PER_DRAW};
        // 4 ребра вдоль каждой оси
        for (double y : new double[]{y0, y1}) for (double z : new double[]{z0, z1})
            edge(p, budget, x0, x1, px, t -> new double[]{t, y, z});
        for (double x : new double[]{x0, x1}) for (double z : new double[]{z0, z1})
            edge(p, budget, y0, y1, py, t -> new double[]{x, t, z});
        for (double x : new double[]{x0, x1}) for (double y : new double[]{y0, y1})
            edge(p, budget, z0, z1, pz, t -> new double[]{x, y, t});
        corner(p, a, P1);
        corner(p, b, P2);
    }

    private void edge(Player p, int[] budget, double from, double to, double near, Function<Double, double[]> at) {
        Location l = p.getLocation();
        double s = Math.max(from, Math.floor((near - RADIUS) / STEP) * STEP), e = Math.min(to, near + RADIUS);
        for (double t = s; t <= e + 1e-6 && budget[0] > 0; t += STEP) {
            double[] c = at.apply(t);
            double dx = c[0] - l.getX(), dy = c[1] - l.getY(), dz = c[2] - l.getZ();
            if (dx * dx + dy * dy + dz * dz > RADIUS * RADIUS) continue;
            p.spawnParticle(Particle.DUST, c[0], c[1], c[2], 1, 0, 0, 0, 0, EDGE, true);
            budget[0]--;
        }
    }

    /** Угол выделения — отдельный цвет: pos1 красный, pos2 синий. */
    private void corner(Player p, Pos c, Particle.DustOptions color) {
        if (p.getLocation().distanceSquared(new Location(p.getWorld(), c.bx() + 0.5, c.by() + 0.5, c.bz() + 0.5)) > RADIUS * RADIUS) return;
        p.spawnParticle(Particle.DUST, c.bx() + 0.5, c.by() + 0.5, c.bz() + 0.5, 6, 0.25, 0.25, 0.25, 0, color, true);
    }
}
