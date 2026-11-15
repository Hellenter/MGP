package ru.mgp.world;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Вырезает прямоугольник чанков из мира Minecraft в новую папку на уровне файлов .mca.
 * Чанки копируются байт-в-байт (NBT не разбирается), остальные чанки региона отбрасываются.
 * Работает для папок region/, entities/ и poi/ — у них одинаковый формат.
 */
public final class RegionCutter {
    private static final int SECTOR = 4096;
    private static final String[] DIRS = {"region", "entities", "poi"};

    public record Stats(int chunks, long bytes) {}

    private RegionCutter() {}

    /** Ищет папку измерения «верхний мир» (где лежит region/) — в разных версиях она в разных местах. */
    public static Path findOverworldDir(Path world) throws IOException {
        if (Files.isDirectory(world.resolve("region"))) return world;
        try (Stream<Path> s = Files.walk(world, 4)) {
            return s.filter(p -> p.getFileName().toString().equals("region") && Files.isDirectory(p))
                    .map(Path::getParent)
                    .filter(p -> {
                        String rel = world.relativize(p).toString().replace('\\', '/');
                        return !rel.contains("DIM-1") && !rel.contains("DIM1") && !rel.contains("nether") && !rel.contains("end");
                    })
                    .findFirst().orElseThrow(() -> new IOException("В мире не найдена папка region: " + world));
        }
    }

    /**
     * Копирует из srcWorld в dstWorld чанки в диапазоне [minCx..maxCx] × [minCz..maxCz] и level.dat.
     * dstWorld очищается заранее вызывающим кодом.
     */
    public static Stats cutWorld(Path srcWorld, Path dstWorld, int minCx, int minCz, int maxCx, int maxCz) throws IOException {
        Path srcDim = findOverworldDir(srcWorld);
        Path dstDim = dstWorld.resolve(srcWorld.relativize(srcDim).toString());
        Files.createDirectories(dstDim);
        for (String f : new String[]{"level.dat"}) {
            Path p = srcWorld.resolve(f);
            if (Files.exists(p)) Files.copy(p, dstWorld.resolve(f), StandardCopyOption.REPLACE_EXISTING);
        }
        int chunks = 0;
        long bytes = 0;
        for (String dir : DIRS) {
            Path s = srcDim.resolve(dir);
            if (!Files.isDirectory(s)) continue;
            Path d = dstDim.resolve(dir);
            Files.createDirectories(d);
            for (int rx = Math.floorDiv(minCx, 32); rx <= Math.floorDiv(maxCx, 32); rx++) {
                for (int rz = Math.floorDiv(minCz, 32); rz <= Math.floorDiv(maxCz, 32); rz++) {
                    Path in = s.resolve("r." + rx + "." + rz + ".mca");
                    if (!Files.exists(in) || Files.size(in) < 2 * SECTOR) continue;
                    Stats st = cutRegion(in, d.resolve(in.getFileName().toString()), rx, rz, minCx, minCz, maxCx, maxCz);
                    if (dir.equals("region")) chunks += st.chunks;
                    bytes += st.bytes;
                }
            }
        }
        return new Stats(chunks, bytes);
    }

    static Stats cutRegion(Path in, Path out, int rx, int rz, int minCx, int minCz, int maxCx, int maxCz) throws IOException {
        try (RandomAccessFile src = new RandomAccessFile(in.toFile(), "r")) {
            byte[] header = new byte[2 * SECTOR];
            src.readFully(header);
            ByteBuffer hb = ByteBuffer.wrap(header);
            byte[] newHeader = new byte[2 * SECTOR];
            ByteBuffer nh = ByteBuffer.wrap(newHeader);
            List<byte[]> blobs = new ArrayList<>();
            int nextSector = 2;
            int kept = 0;
            for (int i = 0; i < 1024; i++) {
                int cx = rx * 32 + (i & 31);
                int cz = rz * 32 + (i >> 5);
                int loc = hb.getInt(i * 4);
                if (loc == 0 || cx < minCx || cx > maxCx || cz < minCz || cz > maxCz) continue;
                int offset = loc >>> 8, count = loc & 0xFF;
                if (offset < 2 || count == 0 || (long) (offset + count) * SECTOR > src.length()) continue;
                byte[] data = new byte[count * SECTOR];
                src.seek((long) offset * SECTOR);
                src.readFully(data);
                blobs.add(data);
                nh.putInt(i * 4, (nextSector << 8) | count);
                nh.putInt(SECTOR + i * 4, hb.getInt(SECTOR + i * 4)); // timestamp
                nextSector += count;
                kept++;
                // чанк, вынесенный во внешний файл (флаг 128 в типе сжатия)
                if ((data[4] & 0x80) != 0) {
                    Path ext = in.resolveSibling("c." + cx + "." + cz + ".mcc");
                    if (Files.exists(ext)) Files.copy(ext, out.resolveSibling(ext.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (kept == 0) return new Stats(0, 0);
            long total = 0;
            try (RandomAccessFile dst = new RandomAccessFile(out.toFile(), "rw")) {
                dst.setLength(0);
                dst.write(newHeader);
                for (byte[] b : blobs) dst.write(b);
                total = dst.length();
            }
            return new Stats(kept, total);
        }
    }
}
