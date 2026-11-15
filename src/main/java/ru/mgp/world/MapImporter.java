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

    public record Request(World world, String mapId, String modeId, Region box, String displayName, boolean detectChests, int margin) {}

    public record Result(String mapId, String template, int chunks, long bytes, int chests, boolean created, boolean modeExists) {
        public String summary() {
            return String.format(Locale.ROOT, "карта %s: %d чанков, %.1f МБ, сундуков найдено: %d%s%s", mapId, chunks,
                    bytes / 1024.0 / 1024.0, chests, created ? " (новая)" : " (обновлена)",
                    modeExists ? "" : ". Режима с таким id пока нет — карта появится в играх, когда он будет создан");
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
        Region box = r.box();
        int minCx = Math.floorDiv(box.minX(), 16) - r.margin(), maxCx = Math.floorDiv(box.maxX(), 16) + r.margin();
        int minCz = Math.floorDiv(box.minZ(), 16) - r.margin(), maxCz = Math.floorDiv(box.maxZ(), 16) + r.margin();

        CompletableFuture<List<String>> chestsF = r.detectChests() ? findChests(w, box, minCx, minCz, maxCx, maxCz)
                : CompletableFuture.completedFuture(null);

        chestsF.whenComplete((chests, ex) -> {
            if (ex != null) { result.completeExceptionally(ex); return; }
            w.save(true); // сбросить изменения на диск перед копированием файлов
            Path src = w.getWorldFolder().toPath();
            Path dst = plugin.worlds().templatesDir().resolve(r.mapId());
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    FileUtil.deleteRecursive(dst);
                    RegionCutter.Stats st = RegionCutter.cutWorld(src, dst, minCx, minCz, maxCx, maxCz);
                    if (st.chunks() == 0) throw new IOException("В этой области нет сгенерированных чанков — проверь координаты и мир");
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        try {
                            boolean created = writeMapConfig(r, chests);
                            plugin.registry().load();
                            result.complete(new Result(r.mapId(), r.mapId(), st.chunks(), st.bytes(), chests == null ? 0 : chests.size(), created,
                                    plugin.registry().mode(r.modeId()) != null));
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

    /** Загружает чанки области асинхронно и собирает контейнеры: "x y z tier". */
    private CompletableFuture<List<String>> findChests(World w, Region box, int minCx, int minCz, int maxCx, int maxCz) {
        Map<String, String> tiers = new HashMap<>();
        ConfigurationSection cs = plugin.getConfig().getConfigurationSection("import.chest-tiers");
        if (cs != null) for (String k : cs.getKeys(false)) tiers.put(k.toUpperCase(Locale.ROOT), cs.getString(k));
        else { tiers.put("CHEST", "tier1"); tiers.put("TRAPPED_CHEST", "tier2"); }

        List<String> found = Collections.synchronizedList(new ArrayList<>());
        List<CompletableFuture<?>> all = new ArrayList<>();
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                all.add(w.getChunkAtAsync(cx, cz, false).thenAccept((Chunk c) -> {
                    if (c == null) return;
                    for (BlockState st : c.getTileEntities()) {
                        if (!(st instanceof Container)) continue;
                        String tier = tiers.get(st.getType().name());
                        if (tier == null || !box.contains(st.getX(), st.getY(), st.getZ())) continue;
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

    private boolean writeMapConfig(Request r, List<String> chests) throws IOException {
        File f = plugin.registry().mapFile(r.mapId());
        boolean created = !f.exists();
        YamlConfiguration y = created ? new YamlConfiguration() : YamlConfiguration.loadConfiguration(f);
        Region b = r.box();
        y.set("mode", r.modeId().toLowerCase(Locale.ROOT));
        if (created || r.displayName() != null) y.set("display-name", r.displayName() != null ? r.displayName() : "<yellow>" + r.mapId());
        if (created) y.set("icon", "FILLED_MAP");
        y.set("template", r.mapId());
        y.set("source.world", r.world().getName());
        y.set("source.min", b.min().serialize());
        y.set("source.max", b.max().serialize());
        double cx = (b.minX() + b.maxX() + 1) / 2.0, cz = (b.minZ() + b.maxZ() + 1) / 2.0;
        if (created || !y.isSet("center")) y.set("center", new Pos(cx, b.minY(), cz, 0, 0).serialize());
        if (created || !y.isSet("border-size")) y.set("border-size", Math.max(b.maxX() - b.minX(), b.maxZ() - b.minZ()) + 10);
        if (created || !y.isSet("spectator")) y.set("spectator", new Pos(cx, b.maxY() + 5, cz, 0, 90).serialize());
        if (!y.isSet("spawns")) y.set("spawns", new ArrayList<String>());
        if (chests != null) y.set("chests", chests);
        f.getParentFile().mkdirs();
        y.save(f);
        return created;
    }
}
