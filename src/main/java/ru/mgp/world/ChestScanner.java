package ru.mgp.world;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

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
        Path regionDir = dimDir.resolve("region");
        List<Found> out = new ArrayList<>();
        int minCx = Math.floorDiv(minX, 16), maxCx = Math.floorDiv(maxX, 16);
        int minCz = Math.floorDiv(minZ, 16), maxCz = Math.floorDiv(maxZ, 16);
        for (int rx = Math.floorDiv(minCx, 32); rx <= Math.floorDiv(maxCx, 32); rx++) {
            for (int rz = Math.floorDiv(minCz, 32); rz <= Math.floorDiv(maxCz, 32); rz++) {
                Path file = regionDir.resolve("r." + rx + "." + rz + ".mca");
                if (!Files.isRegularFile(file) || Files.size(file) < 8192) continue;
                try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                    for (int cx = Math.max(minCx, rx * 32); cx <= Math.min(maxCx, rx * 32 + 31); cx++) {
                        for (int cz = Math.max(minCz, rz * 32); cz <= Math.min(maxCz, rz * 32 + 31); cz++) {
                            byte[] data = readChunk(raf, regionDir, cx, cz);
                            if (data != null) collect(data, minX, minZ, maxX, maxZ, out);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static byte[] readChunk(RandomAccessFile raf, Path regionDir, int cx, int cz) throws IOException {
        int idx = (cx & 31) + (cz & 31) * 32;
        raf.seek(idx * 4L);
        int loc = raf.readInt();
        int offset = loc >>> 8;
        if (offset == 0) return null;
        raf.seek(offset * 4096L);
        int len = raf.readInt();
        int comp = raf.readUnsignedByte();
        byte[] raw;
        if ((comp & 0x80) != 0) {
            Path ext = regionDir.resolve("c." + cx + "." + cz + ".mcc");
            if (!Files.isRegularFile(ext)) return null;
            raw = Files.readAllBytes(ext);
            comp &= 0x7f;
        } else {
            if (len <= 1 || len > 1 << 24) return null;
            raw = new byte[len - 1];
            raf.readFully(raw);
        }
        InputStream base = new ByteArrayInputStream(raw);
        InputStream in = switch (comp) {
            case 1 -> new GZIPInputStream(base);
            case 2 -> new InflaterInputStream(base);
            case 3 -> base;
            default -> null; // LZ4 и прочее — не поддерживаем
        };
        if (in == null) return null;
        try (in) { return in.readAllBytes(); }
    }

    private static void collect(byte[] data, int minX, int minZ, int maxX, int maxZ, List<Found> out) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        if (in.readByte() != 10) return;
        in.skipBytes(in.readUnsignedShort());
        Object root = read(in, 10);
        if (!(root instanceof java.util.Map<?, ?> m)) return;
        Object list = m.get("block_entities");
        if (list == null && m.get("Level") instanceof java.util.Map<?, ?> level) list = level.get("TileEntities");
        if (!(list instanceof List<?> l)) return;
        for (Object o : l) {
            if (!(o instanceof java.util.Map<?, ?> be)) continue;
            if (!(be.get("id") instanceof String id) || !(be.get("x") instanceof Integer x)
                    || !(be.get("y") instanceof Integer y) || !(be.get("z") instanceof Integer z)) continue;
            if (x < minX || x > maxX || z < minZ || z > maxZ) continue;
            String type = id.substring(id.indexOf(':') + 1).toUpperCase(Locale.ROOT);
            out.add(new Found(x, y, z, type));
        }
    }

    /** Минимальный NBT-ридер: compound -> Map, list -> List, массивы пропускаются. */
    private static Object read(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: { int n = in.readInt(); in.skipBytes(n); return null; }
            case 8: return in.readUTF();
            case 9: {
                int et = in.readByte();
                int n = in.readInt();
                List<Object> l = new ArrayList<>(Math.max(0, Math.min(n, 4096)));
                for (int i = 0; i < n; i++) l.add(read(in, et));
                return l;
            }
            case 10: {
                java.util.Map<String, Object> m = new java.util.HashMap<>();
                while (true) {
                    int t = in.readByte();
                    if (t == 0) return m;
                    String name = in.readUTF();
                    m.put(name, read(in, t));
                }
            }
            case 11: { int n = in.readInt(); in.skipBytes(n * 4); return null; }
            case 12: { int n = in.readInt(); in.skipBytes(n * 8); return null; }
            default: throw new IOException("Неизвестный тип NBT " + type);
        }
    }
}
