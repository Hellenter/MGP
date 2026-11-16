package ru.mgp.command;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import ru.mgp.util.Region;

import java.util.ArrayList;
import java.util.List;

/**
 * Рамки областей для одного игрока на ванильном клиенте — как рамка структурного блока или CUI у WorldEdit.
 *
 * Почему так: сервер отправляет игроку только сущности рядом с ним (дальность отслеживания), поэтому
 * отрезки рамки, поставленные на самих рёбрах, у больших областей пропадали вдали и рамка выглядела пунктиром.
 * Здесь каждое ребро — одна block display, которая стоит рядом с игроком (якорь), а до настоящего места ребро
 * дотягивается сдвигом в трансформации; у display размер 0, клиент не отсекает её по расстоянию. Якорь
 * переезжает за игроком. Толщина ребра растёт с расстоянием, чтобы далёкие рёбра оставались сплошными линиями,
 * а не мерцающими точками тоньше пикселя. Цвет даёт сам блок с полной яркостью; свечение — чтобы видно сквозь стены.
 */
final class Outline {
    private static final float MIN_T = 0.045f, MAX_T = 0.6f, PER_BLOCK = 0.002f;

    /** Ребро или кубик: прямоугольник от (x0,y0,z0) до (x1,y1,z1) в мировых координатах. */
    private record Piece(double x0, double y0, double z0, double x1, double y1, double z1, boolean edge, Material block, Color glow) {}

    private final Plugin plugin;
    private final Player viewer;
    private final World world;
    private final List<Piece> pieces = new ArrayList<>();
    private final List<BlockDisplay> entities = new ArrayList<>();
    private Location anchor;      // где стоят сущности
    private Location laidOutFrom; // позиция игрока при последней раскладке толщины

    Outline(Plugin plugin, Player viewer, World world) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.world = world;
    }

    World world() { return world; }

    /** 12 рёбер коробки по внешним граням блоков. */
    void box(Region r, Material block, Color glow) {
        box(r.minX(), r.minY(), r.minZ(), r.maxX() + 1, r.maxY() + 1, r.maxZ() + 1, block, glow);
    }

    void box(double x0, double y0, double z0, double x1, double y1, double z1, Material block, Color glow) {
        for (double y : new double[]{y0, y1}) for (double z : new double[]{z0, z1}) pieces.add(new Piece(x0, y, z, x1, y, z, true, block, glow));
        for (double x : new double[]{x0, x1}) for (double z : new double[]{z0, z1}) pieces.add(new Piece(x, y0, z, x, y1, z, true, block, glow));
        for (double x : new double[]{x0, x1}) for (double y : new double[]{y0, y1}) pieces.add(new Piece(x, y, z0, x, y, z1, true, block, glow));
    }

    /** Сплошной кубик (угол выделения, метка точки). */
    void cube(double x0, double y0, double z0, double x1, double y1, double z1, Material block, Color glow) {
        pieces.add(new Piece(x0, y0, z0, x1, y1, z1, false, block, glow));
    }

    boolean isEmpty() { return pieces.isEmpty(); }

    /** Показать или поправить: якорь за игроком, толщина по расстоянию. Звать раз в несколько тиков. */
    void refresh() {
        if (!viewer.isOnline() || !viewer.getWorld().equals(world)) { hide(); return; }
        Location eye = viewer.getEyeLocation();
        boolean spawn = entities.isEmpty() || entities.stream().anyMatch(e -> !e.isValid());
        boolean moveAnchor = anchor == null || anchor.distanceSquared(eye) > 24 * 24;
        boolean relayout = laidOutFrom == null || laidOutFrom.distanceSquared(eye) > 3 * 3;
        if (!spawn && !moveAnchor && !relayout) return;
        if (spawn) {
            hide();
            anchor = blockAligned(eye);
        } else if (moveAnchor) {
            anchor = blockAligned(eye);
            for (BlockDisplay e : entities) e.teleport(anchor);
        }
        laidOutFrom = eye.clone();
        for (int i = 0; i < pieces.size(); i++) {
            Piece pc = pieces.get(i);
            Transformation tf = transform(pc, eye);
            if (spawn) {
                Location at = anchor;
                BlockDisplay d = world.spawn(at, BlockDisplay.class, x -> {
                    x.setVisibleByDefault(false);
                    x.setPersistent(false);
                    x.setBlock(pc.block().createBlockData());
                    x.setBrightness(new Display.Brightness(15, 15));
                    x.setGlowing(true);
                    x.setGlowColorOverride(pc.glow());
                    x.setViewRange(16f);
                    x.setDisplayWidth(0f);
                    x.setDisplayHeight(0f);
                    x.setInterpolationDuration(0);
                    x.setTeleportDuration(0);
                    x.setTransformation(tf);
                });
                viewer.showEntity(plugin, d);
                entities.add(d);
            } else {
                entities.get(i).setTransformation(tf);
            }
        }
    }

    private static Location blockAligned(Location l) {
        return new Location(l.getWorld(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    /** Сдвиг от якоря и масштаб куска; ребро толще, чем дальше оно от глаз. */
    private Transformation transform(Piece pc, Location eye) {
        float t = 0;
        if (pc.edge()) {
            double cx = clamp(eye.getX(), pc.x0(), pc.x1()), cy = clamp(eye.getY(), pc.y0(), pc.y1()), cz = clamp(eye.getZ(), pc.z0(), pc.z1());
            double d = Math.sqrt(sq(cx - eye.getX()) + sq(cy - eye.getY()) + sq(cz - eye.getZ()));
            t = (float) Math.max(MIN_T, Math.min(MAX_T, MIN_T + d * PER_BLOCK));
        }
        float h = t / 2;
        Vector3f tr = new Vector3f((float) (pc.x0() - anchor.getX()) - h, (float) (pc.y0() - anchor.getY()) - h, (float) (pc.z0() - anchor.getZ()) - h);
        Vector3f sc = new Vector3f((float) (pc.x1() - pc.x0()) + t, (float) (pc.y1() - pc.y0()) + t, (float) (pc.z1() - pc.z0()) + t);
        return new Transformation(tr, new AxisAngle4f(), sc, new AxisAngle4f());
    }

    private static double clamp(double v, double a, double b) { return Math.max(Math.min(a, b), Math.min(Math.max(a, b), v)); }

    private static double sq(double v) { return v * v; }

    /** Убрать сущности (рамку можно показать снова через refresh). */
    void hide() {
        for (BlockDisplay e : entities) if (e.isValid()) e.remove();
        entities.clear();
        anchor = null;
        laidOutFrom = null;
    }
}
