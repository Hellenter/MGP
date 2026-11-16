package ru.mgp.world;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Находит блоки с данными (сундуки и т.п.) прямо в файлах региона (.mca), без загрузки чанков в сервер.
 * Читает только block_entities: id и координаты.
 */
public final class ChestScanner {
    private ChestScanner() {}

    /** Найденный блок: id без "minecraft:" в верхнем регистре (CHEST, TRAPPED_CHEST, BARREL...) и id предметов внутри (IRON_INGOT...). */
    public record Found(int x, int y, int z, String type, List<String> items) {
        public Found(int x, int y, int z, String type) { this(x, y, z, type, List.of()); }
    }

    /** Контейнер с тиром лута. */
    public record Tiered(int x, int y, int z, String tier) {}

    /** Хранилища, которые очищаются в режимах без лута: сундуки, бочки, шалкеры. */
    public static boolean isStorage(String type) {
        return type.equals("CHEST") || type.equals("TRAPPED_CHEST") || type.equals("BARREL") || type.endsWith("SHULKER_BOX");
    }

    /**
     * Тир по предмету-маркеру: в контейнере лежит только один вид предметов (например, железный слиток),
     * и он есть в markers. Сундук с обычным лутом (разные предметы) маркером не считается.
     */
    public static String markerTier(List<String> items, Map<String, String> markers) {
        if (items.isEmpty() || markers.isEmpty()) return null;
        String first = items.get(0);
        for (String s : items) if (!s.equals(first)) return null;
        return markers.get(first);
    }

    /** Контейнеры с маркерами (любого типа: сундук, бочка, шалкер...). */
    public static List<Tiered> marked(List<Found> found, Map<String, String> markers) {
        List<Tiered> out = new ArrayList<>();
        for (Found f : found) {
            String t = markerTier(f.items(), markers);
            if (t != null) out.add(new Tiered(f.x(), f.y(), f.z(), t));
        }
        return out;
    }

    /** Старый способ — тир по типу блока (import.chest-tiers: CHEST -> tier1, TRAPPED_CHEST -> tier2). */
    public static List<Tiered> byType(List<Found> found, Map<String, String> typeTiers) {
        List<Tiered> out = new ArrayList<>();
        for (Found f : found) {
            String t = typeTiers.get(f.type());
            if (t != null) out.add(new Tiered(f.x(), f.y(), f.z(), t));
        }
        return out;
    }

    /** Если на карте есть хоть один маркер — только помеченные контейнеры, иначе по типу блока. */
    public static List<Tiered> tiers(List<Found> found, Map<String, String> markers, Map<String, String> typeTiers) {
        List<Tiered> m = marked(found, markers);
        return m.isEmpty() ? byType(found, typeTiers) : m;
    }

    /** dimDir — папка, где лежит region/ (шаблон или измерение мира). Границы по X/Z включительно. */
    public static List<Found> scan(Path dimDir, int minX, int minZ, int maxX, int maxZ) throws IOException {
        List<Found> out = new ArrayList<>();
        McaReader.forEachChunk(dimDir, Math.floorDiv(minX, 16), Math.floorDiv(minZ, 16), Math.floorDiv(maxX, 16), Math.floorDiv(maxZ, 16),
                (cx, cz, root) -> { if (root != null) collect(root, minX, minZ, maxX, maxZ, out); });
        return out;
    }

    private static void collect(Map<?, ?> m, int minX, int minZ, int maxX, int maxZ, List<Found> out) {
        Object list = m.get("block_entities");
        if (list == null && m.get("Level") instanceof Map<?, ?> level) list = level.get("TileEntities");
        if (!(list instanceof List<?> l)) return;
        for (Object o : l) {
            if (!(o instanceof Map<?, ?> be)) continue;
            if (!(be.get("id") instanceof String id) || !(be.get("x") instanceof Integer x)
                    || !(be.get("y") instanceof Integer y) || !(be.get("z") instanceof Integer z)) continue;
            if (x < minX || x > maxX || z < minZ || z > maxZ) continue;
            String type = id.substring(id.indexOf(':') + 1).toUpperCase(Locale.ROOT);
            List<String> items = new ArrayList<>();
            if (be.get("Items") instanceof List<?> il) for (Object io : il) {
                if (io instanceof Map<?, ?> it && it.get("id") instanceof String iid)
                    items.add(iid.substring(iid.indexOf(':') + 1).toUpperCase(Locale.ROOT));
            }
            out.add(new Found(x, y, z, type, items));
        }
    }
}
