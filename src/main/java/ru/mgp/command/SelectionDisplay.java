package ru.mgp.command;

import org.bukkit.Bukkit;
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
import ru.mgp.util.Pos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Рамка выделения /mgp setup pos1 / pos2 — как у структурного блока или CUI для WorldEdit:
 * тонкие светящиеся рёбра коробки (видны сквозь блоки) и подсвеченные углы (pos1 красный, pos2 синий).
 * Сделано на block display, поэтому работает на ванильном клиенте без модов. Видит только сам игрок.
 * Сущности не сохраняются в мир; если чанк выгрузился, отрезок рамки появится снова, когда чанк загрузится.
 */
final class SelectionDisplay {
    private static final float EDGE = 0.045f;       // толщина ребра
    private static final double SEGMENT = 32;       // длинные рёбра режем на отрезки (дальность отслеживания сущностей)
    private static final Color EDGE_GLOW = Color.WHITE;
    private static final Color P1_GLOW = Color.fromRGB(255, 60, 60);
    private static final Color P2_GLOW = Color.fromRGB(60, 140, 255);

    /** Один элемент рамки: где стоит сущность и как её растянуть. */
    private record Piece(double x, double y, double z, Vector3f translation, Vector3f scale, Material block, Color glow) {}

    private static final class Frame {
        final String world;
        final List<Piece> pieces = new ArrayList<>();
        final BlockDisplay[] entities;
        Frame(String world, List<Piece> pieces) {
            this.world = world;
            this.pieces.addAll(pieces);
            this.entities = new BlockDisplay[pieces.size()];
        }
    }

    private final Plugin plugin;
    private final Map<UUID, Pos> pos1, pos2;
    private final Map<UUID, String> world;
    private final Map<UUID, Frame> frames = new HashMap<>();

    SelectionDisplay(Plugin plugin, Map<UUID, Pos> pos1, Map<UUID, Pos> pos2, Map<UUID, String> world) {
        this.plugin = plugin;
        this.pos1 = pos1;
        this.pos2 = pos2;
        this.world = world;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 40L);
    }

    /** Перестроить рамку игрока после изменения выделения. */
    void update(Player p) {
        UUID id = p.getUniqueId();
        remove(id);
        Pos a = pos1.get(id), b = pos2.get(id);
        String w = world.get(id);
        if ((a == null && b == null) || w == null) return;
        if (a == null) a = b;
        if (b == null) b = a;
        Frame f = new Frame(w, build(a, pos1.containsKey(id) ? a : null, pos2.containsKey(id) ? b : null, b));
        frames.put(id, f);
        fill(p, f);
    }

    void remove(UUID id) {
        Frame f = frames.remove(id);
        if (f == null) return;
        for (BlockDisplay e : f.entities) if (e != null && e.isValid()) e.remove();
    }

    void removeAll() {
        for (UUID id : new ArrayList<>(frames.keySet())) remove(id);
    }

    /** Убирает рамки вышедших игроков и восстанавливает отрезки, пропавшие при выгрузке чанков. */
    private void tick() {
        for (UUID id : new ArrayList<>(frames.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) { remove(id); continue; }
            fill(p, frames.get(id));
        }
    }

    private void fill(Player p, Frame f) {
        World w = Bukkit.getWorld(f.world);
        if (w == null) return;
        for (int i = 0; i < f.entities.length; i++) {
            BlockDisplay e = f.entities[i];
            if (e != null && e.isValid()) continue;
            Piece pc = f.pieces.get(i);
            if (!w.isChunkLoaded((int) Math.floor(pc.x()) >> 4, (int) Math.floor(pc.z()) >> 4)) continue;
            f.entities[i] = w.spawn(new Location(w, pc.x(), pc.y(), pc.z()), BlockDisplay.class, d -> {
                d.setVisibleByDefault(false);
                d.setPersistent(false);
                d.setBlock(pc.block().createBlockData());
                d.setTransformation(new Transformation(pc.translation(), new AxisAngle4f(), pc.scale(), new AxisAngle4f()));
                d.setBrightness(new Display.Brightness(15, 15));
                d.setGlowing(true);
                d.setGlowColorOverride(pc.glow());
                d.setViewRange(4f);
            });
            p.showEntity(plugin, f.entities[i]);
        }
    }

    /** 12 рёбер коробки по внешним граням блоков + кубики на углах pos1 / pos2. */
    private static List<Piece> build(Pos a, Pos c1, Pos c2, Pos b) {
        double x0 = Math.min(a.bx(), b.bx()), y0 = Math.min(a.by(), b.by()), z0 = Math.min(a.bz(), b.bz());
        double x1 = Math.max(a.bx(), b.bx()) + 1, y1 = Math.max(a.by(), b.by()) + 1, z1 = Math.max(a.bz(), b.bz()) + 1;
        List<Piece> out = new ArrayList<>();
        for (double y : new double[]{y0, y1}) for (double z : new double[]{z0, z1}) edge(out, 0, x0, x1, y, z);
        for (double x : new double[]{x0, x1}) for (double z : new double[]{z0, z1}) edge(out, 1, y0, y1, x, z);
        for (double x : new double[]{x0, x1}) for (double y : new double[]{y0, y1}) edge(out, 2, z0, z1, x, y);
        if (c1 != null) corner(out, c1, Material.RED_STAINED_GLASS, P1_GLOW);
        if (c2 != null && !(c1 != null && c1.bx() == c2.bx() && c1.by() == c2.by() && c1.bz() == c2.bz()))
            corner(out, c2, Material.BLUE_STAINED_GLASS, P2_GLOW);
        return out;
    }

    /** Ребро вдоль оси axis (0=X, 1=Y, 2=Z) от from до to; u, v — две другие координаты. */
    private static void edge(List<Piece> out, int axis, double from, double to, double u, double v) {
        int n = Math.max(1, (int) Math.ceil((to - from) / SEGMENT));
        double step = (to - from) / n;
        for (int i = 0; i < n; i++) {
            double s = from + i * step, len = step;
            boolean last = i == n - 1;
            float l = (float) len + (last ? EDGE : 0f); // перекрыть угол на конце ребра
            Vector3f tr, sc;
            double x, y, z;
            switch (axis) {
                case 0 -> { x = s; y = u; z = v; tr = new Vector3f(-EDGE / 2, -EDGE / 2, -EDGE / 2); sc = new Vector3f(l, EDGE, EDGE); }
                case 1 -> { x = u; y = s; z = v; tr = new Vector3f(-EDGE / 2, -EDGE / 2, -EDGE / 2); sc = new Vector3f(EDGE, l, EDGE); }
                default -> { x = u; y = v; z = s; tr = new Vector3f(-EDGE / 2, -EDGE / 2, -EDGE / 2); sc = new Vector3f(EDGE, EDGE, l); }
            }
            out.add(new Piece(x, y, z, tr, sc, Material.WHITE_CONCRETE, EDGE_GLOW));
        }
    }

    private static void corner(List<Piece> out, Pos c, Material m, Color glow) {
        float g = 0.02f;
        out.add(new Piece(c.bx(), c.by(), c.bz(), new Vector3f(-g, -g, -g), new Vector3f(1 + 2 * g, 1 + 2 * g, 1 + 2 * g), m, glow));
    }
}
