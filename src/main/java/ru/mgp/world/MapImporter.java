package ru.mgp.world;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.mgp.MGPPlugin;
import ru.mgp.util.FileUtil;
import ru.mgp.util.Pos;
import ru.mgp.util.Region;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Импорт готовой карты из большого мира: область вырезается в отдельный шаблон
 * (только нужные чанки), а в maps/<id>.yml записываются режим, название, центр, барьер и найденные сундуки.
 * Координаты в шаблоне те же, что в исходном мире — спавны можно ставить прямо в исходном мире.
 */
public final class MapImporter {
    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,32}");

    /**
     * lobby=true: вырезать область как лобби ожидания режима (без maps/*.yml, без поиска сундуков).
     * trim=true: «оптимизация границ» — обрезать края области, где только воздух (запас — import.optimize-padding блоков).
     */
    public record Request(World world, String mapId, String modeId, Region box, String displayName, boolean detectChests, int margin,
                          boolean lobby, boolean trim) {
        public Request(World world, String mapId, String modeId, Region box, String displayName, boolean detectChests, int margin, boolean lobby) {
            this(world, mapId, modeId, box, displayName, detectChests, margin, lobby, false);
        }
        public Request(World world, String mapId, String modeId, Region box, String displayName, boolean detectChests, int margin) {
            this(world, mapId, modeId, box, displayName, detectChests, margin, false, false);
        }
    }

    /** box — итоговая область карты (после оптимизации границ), original — выделенная. */
    public record Result(String mapId, String template, int chunks, long bytes, int chests, boolean created, boolean modeExists,
                         Region box, Region original) {
        public boolean trimmed() { return !box.equals(original); }

        public String summary() {
            return String.format(Locale.ROOT, "карта %s: %d чанков, %.1f МБ, сундуков найдено: %d%s%s%s", mapId, chunks,
                    bytes / 1024.0 / 1024.0, chests, created ? " (новая)" : " (обновлена)",
                    trimmed() ? ". Границы оптимизированы: " + size(original) + " → " + size(box)
                            + " (" + box.min().serialize() + " — " + box.max().serialize() + ")" : "",
                    modeExists ? "" : ". Режима с таким id пока нет — карта появится в играх, когда он будет создан");
        }

        private static String size(Region r) {
            return (r.maxX() - r.minX() + 1) + "×" + (r.maxY() - r.minY() + 1) + "×" + (r.maxZ() - r.minZ() + 1);
        }
    }

    private final MGPPlugin plugin;
    private boolean busy;

    public MapImporter(MGPPlugin plugin) { this.plugin = plugin; }

    /** Запускать в основном потоке. Future завершается в основном потоке. */
    public CompletableFuture<Result> run(Request r) {
        CompletableFuture<Result> result = new CompletableFuture<>();
        try {
            if (busy) throw new IllegalStateException("Уже идёт импорт другой карты, подожди");
            if (!ID.matcher(r.mapId()).matches()) throw new IllegalArgumentException("Id карты: только a-z, 0-9 и _ (до 32 символов)");
            if (!ID.matcher(r.modeId()).matches()) throw new IllegalArgumentException("Id режима: только a-z, 0-9 и _");
            if (r.world().getName().startsWith(WorldService.PREFIX)) throw new IllegalArgumentException("Нельзя импортировать из игрового мира сессии");
            int sx = r.box().maxX() - r.box().minX() + 1, sz = r.box().maxZ() - r.box().minZ() + 1;
            if ((long) sx * sz > 2048L * 2048L) throw new IllegalArgumentException("Слишком большая область (" + sx + "×" + sz + ")");
        } catch (RuntimeException e) {
            result.completeExceptionally(e);
            return result;
        }
        busy = true;
        result.whenComplete((x, e) -> busy = false);

        World w = r.world();
        // список сундуков нужен только режимам с лутом (если режима ещё нет — пишем, вдруг он будет с лутом)
        var mode = plugin.registry().mode(r.modeId());
        boolean scan = r.detectChests() && !r.lobby() && (mode == null || mode.chestLoot());
        Map<String, String> tiers = chestTiers();
        Map<String, String> markers = mode != null ? mode.lootMarkers : ru.mgp.config.ModeConfig.DEFAULT_MARKERS;
        int padding = Math.max(0, plugin.getConfig().getInt("import.optimize-padding", 2));
        CompletableFuture.completedFuture(null).whenComplete((ignored, ex) -> {
            w.save(true); // сбросить изменения на диск перед копированием файлов
            Path src = w.getWorldFolder().toPath();
            Path dst = plugin.worlds().templatesDir().resolve(r.mapId());
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    waitForQuietDisk(RegionCutter.findOverworldDir(src));
                    Region box = r.box();
                    if (r.trim()) {
                        Region solid = BoundsScanner.scan(RegionCutter.findOverworldDir(src), box);
                        if (solid == null) throw new IOException("В выделенной области только воздух — проверь координаты и мир");
                        box = new Region(Math.max(box.minX(), solid.minX() - padding), Math.max(box.minY(), solid.minY() - padding),
                                Math.max(box.minZ(), solid.minZ() - padding), Math.min(box.maxX(), solid.maxX() + padding),
                                Math.min(box.maxY(), solid.maxY() + padding), Math.min(box.maxZ(), solid.maxZ() + padding));
                    }
                    // Копируем квадрат под барьер (барьер квадратный) плюс запас в import.margin-chunks чанков:
                    // край карты за барьером, игроки его не видят вблизи.
                    int half = r.lobby() ? 0 : (Math.max(box.maxX() - box.minX(), box.maxZ() - box.minZ()) + 2) / 2;
                    int ccx = Math.floorDiv(box.minX() + box.maxX(), 2), ccz = Math.floorDiv(box.minZ() + box.maxZ(), 2);
                    int minCx = Math.floorDiv(Math.min(box.minX(), ccx - half), 16) - r.margin();
                    int maxCx = Math.floorDiv(Math.max(box.maxX(), ccx + half), 16) + r.margin();
                    int minCz = Math.floorDiv(Math.min(box.minZ(), ccz - half), 16) - r.margin();
                    int maxCz = Math.floorDiv(Math.max(box.maxZ(), ccz + half), 16) + r.margin();
                    Region fbox = box;
                    FileUtil.deleteRecursive(dst);
                    RegionCutter.Stats st = RegionCutter.cutWorld(src, dst, minCx, minCz, maxCx, maxCz);
                    if (st.chunks() == 0) throw new IOException("В этой области нет сгенерированных чанков — проверь координаты и мир");
                    // сундуки ищем в уже вырезанных файлах: getTileEntities() на живом сервере их не возвращал
                    List<String> chests = scan ? new ArrayList<>() : null;
                    if (scan) {
                        // есть сундуки с маркерами (железный/золотой слиток) — только они, иначе по типу блока
                        var found = ChestScanner.scan(RegionCutter.findOverworldDir(dst), fbox.minX(), fbox.minZ(), fbox.maxX(), fbox.maxZ());
                        for (ChestScanner.Tiered t : ChestScanner.tiers(found, markers, tiers))
                            chests.add(t.x() + " " + t.y() + " " + t.z() + " " + t.tier());
                        Collections.sort(chests);
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        try {
                            boolean created = r.lobby() || writeMapConfig(r, fbox, chests);
                            plugin.registry().load();
                            result.complete(new Result(r.mapId(), r.mapId(), st.chunks(), st.bytes(), chests == null ? 0 : chests.size(), created,
                                    plugin.registry().mode(r.modeId()) != null, fbox, r.box()));
                        } catch (Exception e) {
                            result.completeExceptionally(e);
                        }
                    });
                } catch (Exception e) {
                    Bukkit.getScheduler().runTask(plugin, () -> result.completeExceptionally(e));
                }
            });
        });
        return result;
    }

    /**
     * save(true) возвращается раньше, чем сервер дописал все чанки в .mca (проверено на Paper 26.3:
     * свежий чанк попал в файл уже после начала вырезки). Ждём, пока файлы региона перестанут меняться.
     */
    private static void waitForQuietDisk(Path dim) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        long last = -1;
        while (System.currentTimeMillis() < deadline) {
            long newest = 0;
            for (String d : new String[]{"region", "entities"}) {
                Path dir = dim.resolve(d);
                if (!java.nio.file.Files.isDirectory(dir)) continue;
                try (var files = java.nio.file.Files.list(dir)) {
                    for (Path f : (Iterable<Path>) files::iterator)
                        newest = Math.max(newest, java.nio.file.Files.getLastModifiedTime(f).toMillis());
                }
            }
            if (newest == last && System.currentTimeMillis() - newest > 1000) return;
            last = newest;
            Thread.sleep(400);
        }
    }

    private Map<String, String> chestTiers() { return ru.mgp.util.Cfg.chestTiers(plugin.getConfig()); }

    /** Загружает чанки области асинхронно и собирает контейнеры: "x y z tier". (Не используется: см. ChestScanner.) */
    private CompletableFuture<List<String>> findChests(World w, Region box, int minCx, int minCz, int maxCx, int maxCz) {
        Map<String, String> tiers = chestTiers();

        List<String> found = Collections.synchronizedList(new ArrayList<>());
        List<CompletableFuture<?>> all = new ArrayList<>();
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                all.add(w.getChunkAtAsync(cx, cz, false).thenAccept((Chunk c) -> {
                    if (c == null) return;
                    for (BlockState st : c.getTileEntities()) {
                        if (!(st instanceof Container)) continue;
                        String tier = tiers.get(st.getType().name());
                        if (tier == null || st.getX() < box.minX() || st.getX() > box.maxX() || st.getZ() < box.minZ() || st.getZ() > box.maxZ()) continue;
                        found.add(st.getX() + " " + st.getY() + " " + st.getZ() + " " + tier);
                    }
                }));
            }
        }
        return CompletableFuture.allOf(all.toArray(new CompletableFuture[0])).thenApply(v -> {
            List<String> out = new ArrayList<>(found);
            Collections.sort(out);
            return out;
        });
    }

    /** source.min/max — выделенная область (по ней пересоздаётся шаблон), center/border — по итоговой области b. */
    private boolean writeMapConfig(Request r, Region b, List<String> chests) throws IOException {
        File f = plugin.registry().mapFile(r.mapId());
        boolean created = !f.exists();
        YamlConfiguration y = created ? new YamlConfiguration() : YamlConfiguration.loadConfiguration(f);
        y.set("mode", r.modeId().toLowerCase(Locale.ROOT));
        if (created || r.displayName() != null) y.set("display-name", r.displayName() != null ? r.displayName() : "<yellow>" + r.mapId());
        if (created) y.set("icon", "FILLED_MAP");
        y.set("template", r.mapId());
        y.set("source.world", r.world().getName());
        y.set("source.min", r.box().min().serialize());
        y.set("source.max", r.box().max().serialize());
        y.set("source.optimized", r.trim());
        double cx = (b.minX() + b.maxX() + 1) / 2.0, cz = (b.minZ() + b.maxZ() + 1) / 2.0;
        // Центр и барьер — всегда по выделенной области: барьер не выпускает игроков к срезу карты
        // (вокруг области дополнительно копируется import.margin-chunks чанков).
        y.set("center", new Pos(cx, b.minY(), cz, 0, 0).serialize());
        y.set("border-size", Math.max(b.maxX() - b.minX(), b.maxZ() - b.minZ()) + 1);
        if (created || !y.isSet("spectator")) y.set("spectator", new Pos(cx, b.maxY() + 5, cz, 0, 90).serialize());
        if (!y.isSet("spawns")) y.set("spawns", new ArrayList<String>());
        if (chests != null) y.set("chests", chests);
        f.getParentFile().mkdirs();
        y.save(f);
        return created;
    }
}
