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

    /** Найденный блок: id без "minecraft:" в верхнем регистре (CHEST, TRAPPED_CHEST, BARREL...). */
    public record Found(int x, int y, int z, String type) {}

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
            out.add(new Found(x, y, z, type));
        }
    }
}
