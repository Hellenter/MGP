package ru.mgp.world;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Чтение чанков прямо из файлов региона (.mca) без загрузки их в сервер.
 * NBT разбирается в простые объекты: compound -> Map, list -> List, long[] -> long[], прочие массивы пропускаются.
 */
final class McaReader {
    private McaReader() {}

    interface ChunkVisitor {
        /** root == null — чанк есть в файле, но прочитать его не удалось (неизвестное сжатие и т.п.). */
        void visit(int cx, int cz, Map<?, ?> root) throws IOException;
    }

    /** Обходит все сохранённые чанки в прямоугольнике [minCx..maxCx] × [minCz..maxCz] папки dimDir/region. */
    static void forEachChunk(Path dimDir, int minCx, int minCz, int maxCx, int maxCz, ChunkVisitor v) throws IOException {
        Path regionDir = dimDir.resolve("region");
        for (int rx = Math.floorDiv(minCx, 32); rx <= Math.floorDiv(maxCx, 32); rx++) {
            for (int rz = Math.floorDiv(minCz, 32); rz <= Math.floorDiv(maxCz, 32); rz++) {
                Path file = regionDir.resolve("r." + rx + "." + rz + ".mca");
                if (!Files.isRegularFile(file) || Files.size(file) < 8192) continue;
                try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                    for (int cx = Math.max(minCx, rx * 32); cx <= Math.min(maxCx, rx * 32 + 31); cx++) {
                        for (int cz = Math.max(minCz, rz * 32); cz <= Math.min(maxCz, rz * 32 + 31); cz++) {
                            int idx = (cx & 31) + (cz & 31) * 32;
                            raf.seek(idx * 4L);
                            if ((raf.readInt() >>> 8) == 0) continue; // чанка нет
                            Map<?, ?> root = null;
                            try {
                                byte[] data = readChunk(raf, regionDir, cx, cz);
                                if (data != null) root = parse(data);
                            } catch (IOException | RuntimeException e) {
                                root = null;
                            }
                            v.visit(cx, cz, root);
                        }
                    }
                }
            }
        }
    }

    private static byte[] readChunk(RandomAccessFile raf, Path regionDir, int cx, int cz) throws IOException {
        int idx = (cx & 31) + (cz & 31) * 32;
        raf.seek(idx * 4L);
        int offset = raf.readInt() >>> 8;
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

    private static Map<?, ?> parse(byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        if (in.readByte() != 10) return null;
        in.skipBytes(in.readUnsignedShort());
        return read(in, 10) instanceof Map<?, ?> m ? m : null;
    }

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
                Map<String, Object> m = new HashMap<>();
                while (true) {
                    int t = in.readByte();
                    if (t == 0) return m;
                    String name = in.readUTF();
                    m.put(name, read(in, t));
                }
            }
            case 11: { int n = in.readInt(); in.skipBytes(n * 4); return null; }
            case 12: {
                int n = in.readInt();
                if (n < 0 || n > 1 << 16) throw new IOException("Слишком большой массив NBT: " + n);
                long[] a = new long[n];
                for (int i = 0; i < n; i++) a[i] = in.readLong();
                return a;
            }
            default: throw new IOException("Неизвестный тип NBT " + type);
        }
    }
}
