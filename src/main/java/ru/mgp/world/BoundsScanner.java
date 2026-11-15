package ru.mgp.world;

import ru.mgp.util.Region;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * «Оптимизация границ»: находит наименьший прямоугольник внутри области, где есть хоть один блок, кроме воздуха.
 * Читает палитры секций прямо из .mca, сервер чанки не загружает.
 * Чанк, который не удалось прочитать, считается заполненным — лишнего не обрежем.
 */
public final class BoundsScanner {
    private BoundsScanner() {}

    private static final Set<String> AIR = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");

    /** Возвращает границы непустых блоков внутри box или null, если там только воздух. */
    public static Region scan(Path dimDir, Region box) throws IOException {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        McaReader.forEachChunk(dimDir, Math.floorDiv(box.minX(), 16), Math.floorDiv(box.minZ(), 16),
                Math.floorDiv(box.maxX(), 16), Math.floorDiv(box.maxZ(), 16), (cx, cz, root) -> chunk(cx, cz, root, box, b));
        if (b[0] == Integer.MAX_VALUE) return null;
        return new Region(b[0], b[1], b[2], b[3], b[4], b[5]);
    }

    private static void chunk(int cx, int cz, Map<?, ?> root, Region box, int[] b) {
        int x0 = Math.max(box.minX(), cx * 16), x1 = Math.min(box.maxX(), cx * 16 + 15);
        int z0 = Math.max(box.minZ(), cz * 16), z1 = Math.min(box.maxZ(), cz * 16 + 15);
        Object secs = root == null ? null : root.get("sections");
        if (!(secs instanceof List<?> sections)) {
            add(b, x0, box.minY(), z0, x1, box.maxY(), z1); // не смогли прочитать — считаем, что там что-то есть
            return;
        }
        for (Object o : sections) {
            if (!(o instanceof Map<?, ?> sec) || !(sec.get("Y") instanceof Number sy)) continue;
            int y0 = Math.max(box.minY(), sy.intValue() * 16), y1 = Math.min(box.maxY(), sy.intValue() * 16 + 15);
            if (y0 > y1) continue;
            if (!(sec.get("block_states") instanceof Map<?, ?> states) || !(states.get("palette") instanceof List<?> palette)) continue;
            boolean[] solid = new boolean[palette.size()];
            boolean any = false;
            for (int i = 0; i < solid.length; i++) {
                String name = blockName(palette.get(i));
                solid[i] = !AIR.contains(name);
                any |= solid[i];
            }
            if (!any) continue;
            // всё, что эта секция может добавить, уже внутри найденных границ — разбирать не нужно
            if (inside(b, x0, y0, z0, x1, y1, z1)) continue;
            if (palette.size() == 1 || !(states.get("data") instanceof long[] data)) {
                add(b, x0, y0, z0, x1, y1, z1);
                continue;
            }
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
            int perLong = 64 / bits;
            long mask = (1L << bits) - 1;
            int baseY = sy.intValue() * 16;
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    for (int x = x0; x <= x1; x++) {
                        int idx = ((y - baseY) << 8) | ((z & 15) << 4) | (x & 15);
                        int li = idx / perLong;
                        if (li >= data.length) continue;
                        int v = (int) ((data[li] >>> ((idx % perLong) * bits)) & mask);
                        if (v < solid.length && solid[v]) add(b, x, y, z, x, y, z);
                    }
                }
            }
        }
    }

    /**
     * Запись палитры: в 26.x блок без свойств — просто строка, со свойствами — {id, properties} (раньше {Name, Properties});
     * в смешанном списке строки обёрнуты в {"": "..."}. Непонятная запись считается не воздухом.
     */
    private static String blockName(Object e) {
        if (e instanceof String s) return s;
        if (e instanceof Map<?, ?> m) {
            if (m.get("id") instanceof String s) return s;
            if (m.get("Name") instanceof String s) return s;
            if (m.get("") instanceof String s) return s;
        }
        return "?";
    }

    private static boolean inside(int[] b, int x0, int y0, int z0, int x1, int y1, int z1) {
        return x0 >= b[0] && y0 >= b[1] && z0 >= b[2] && x1 <= b[3] && y1 <= b[4] && z1 <= b[5];
    }

    private static void add(int[] b, int x0, int y0, int z0, int x1, int y1, int z1) {
        b[0] = Math.min(b[0], x0); b[1] = Math.min(b[1], y0); b[2] = Math.min(b[2], z0);
        b[3] = Math.max(b[3], x1); b[4] = Math.max(b[4], y1); b[5] = Math.max(b[5], z1);
    }
}
