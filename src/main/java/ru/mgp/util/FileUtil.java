package ru.mgp.util;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

public final class FileUtil {
    /** Файлы/папки, которые нельзя копировать между мирами (дубли UID ломают загрузку). */
    private static final Set<String> SKIP = Set.of("uid.dat", "session.lock", "playerdata", "players", "advancements", "stats",
            "metadata.dat", "DIM-1", "DIM1", "dimensions", "datapacks");

    private FileUtil() {}

    public static void copyWorld(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(src) && SKIP.contains(dir.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (SKIP.contains(file.getFileName().toString())) return FileVisitResult.CONTINUE;
                Files.copy(file, dst.resolve(src.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static void deleteRecursive(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
