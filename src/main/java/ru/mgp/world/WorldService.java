package ru.mgp.world;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import ru.mgp.MGPPlugin;
import ru.mgp.util.FileUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Шаблоны миров лежат в plugins/MGP/templates/<имя>.
 * На каждую сессию шаблон копируется в папку сервера (mgp_...), загружается без автосохранения,
 * а после игры выгружается без сохранения и удаляется. Так карта всегда «чистая».
 */
public final class WorldService {
    public static final String PREFIX = "mgp_";

    private final MGPPlugin plugin;
    private final Path templatesDir;
    private volatile boolean shuttingDown;

    public WorldService(MGPPlugin plugin) {
        this.plugin = plugin;
        this.templatesDir = new File(plugin.getDataFolder(), "templates").toPath();
        try {
            Files.createDirectories(templatesDir);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось создать папку templates", e);
        }
    }

    public Path templatesDir() { return templatesDir; }

    public boolean templateExists(String template) {
        return template != null && Files.isDirectory(templatesDir.resolve(template));
    }

    /** Асинхронно копирует шаблон и загружает мир в основном потоке. Future завершается в основном потоке. */
    public CompletableFuture<World> create(String template, String worldName) {
        CompletableFuture<World> result = new CompletableFuture<>();
        Path src = templatesDir.resolve(template);
        Path dst = dimensionDir(worldName);
        if (!Files.isDirectory(src)) {
            result.completeExceptionally(new IOException("Шаблон не найден: " + src));
            return result;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                FileUtil.deleteRecursive(dst);
                // В шаблоне регионы могут лежать в корне (наш формат) или внутри старой/новой структуры мира.
                FileUtil.copyWorld(RegionCutter.findOverworldDir(src), dst);
            } catch (IOException e) {
                Bukkit.getScheduler().runTask(plugin, () -> result.completeExceptionally(e));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    World w = new WorldCreator(worldName)
                            .generator(new VoidGenerator())
                            .generateStructures(false)
                            .createWorld();
                    if (w == null) throw new IllegalStateException("createWorld вернул null");
                    w.setAutoSave(false);
                    if (!w.getWorldFolder().toPath().toAbsolutePath().normalize().equals(dst.toAbsolutePath().normalize())) {
                        plugin.getLogger().warning("Мир " + worldName + " загружен из " + w.getWorldFolder() + ", а шаблон скопирован в " + dst
                                + " — карта может оказаться пустой. Сообщи разработчику плагина.");
                    }
                    result.complete(w);
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
            });
        });
        return result;
    }

    /**
     * Запасное лобби ожидания, когда шаблона нет: пустой мир с площадкой 15x15 под точкой спавна.
     * Завершается сразу, в основном потоке.
     */
    public CompletableFuture<World> createPlatform(String worldName, ru.mgp.util.Pos spawn) {
        try {
            FileUtil.deleteRecursive(dimensionDir(worldName));
            World w = new WorldCreator(worldName).generator(new VoidGenerator()).generateStructures(false).createWorld();
            if (w == null) throw new IllegalStateException("createWorld вернул null");
            w.setAutoSave(false);
            int cx = spawn.bx(), cy = spawn.by() - 1, cz = spawn.bz();
            for (int dx = -7; dx <= 7; dx++) {
                for (int dz = -7; dz <= 7; dz++) {
                    boolean edge = Math.abs(dx) == 7 || Math.abs(dz) == 7;
                    w.getBlockAt(cx + dx, cy, cz + dz).setType(edge ? org.bukkit.Material.LIGHT_BLUE_STAINED_GLASS : org.bukkit.Material.SMOOTH_STONE, false);
                    if (edge) w.getBlockAt(cx + dx, cy + 1, cz + dz).setType(org.bukkit.Material.GLASS_PANE, true);
                }
            }
            // во временной площадке всегда полдень (12:00 = 6000 тиков), время не идёт
            w.setTime(6000);
            applyGamerules(w, Map.of(daylightRule(), "false"));
            return CompletableFuture.completedFuture(w);
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }

    /**
     * Геймрулы для всех миров игр и лобби ожидания (config.yml → worlds.default-gamerules).
     * По умолчанию random_tick_speed 0: не растут посевы, не опадает листва, не тает снег.
     */
    public Map<String, String> defaultGamerules() {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        Object sec = plugin.getConfig().get("worlds.default-gamerules", null);
        if (sec instanceof org.bukkit.configuration.ConfigurationSection cs) {
            for (String k : cs.getKeys(false)) out.put(k, String.valueOf(cs.get(k)));
        } else {
            out.put("random_tick_speed", "0");
        }
        return out;
    }

    /** Применяет слои геймрулов по порядку: общие, затем режима, затем карты. */
    @SafeVarargs
    public final void applyWorldRules(World w, Map<String, String>... layers) {
        Map<String, String> all = new java.util.LinkedHashMap<>(defaultGamerules());
        for (Map<String, String> l : layers) if (l != null) all.putAll(l);
        applyGamerules(w, all);
    }

    /** Биом по id ("plains", "minecraft:plains"); null — нет такого. */
    public static org.bukkit.block.Biome biome(String id) {
        if (id == null || id.isBlank()) return null;
        org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(id.trim().toLowerCase(java.util.Locale.ROOT));
        return key == null ? null : io.papermc.paper.registry.RegistryAccess.registryAccess()
                .getRegistry(io.papermc.paper.registry.RegistryKey.BIOME).get(key);
    }

    public static java.util.List<String> biomeIds() {
        return io.papermc.paper.registry.RegistryAccess.registryAccess().getRegistry(io.papermc.paper.registry.RegistryKey.BIOME)
                .keyStream().map(k -> k.getNamespace().equals("minecraft") ? k.getKey() : k.toString()).sorted().toList();
    }

    /**
     * Ставит биом в области (по X/Z целиком, по Y — от minY до maxY). Чанки грузятся асинхронно,
     * биом меняется в основном потоке; future завершается, когда всё готово.
     */
    public CompletableFuture<Void> applyBiome(World w, ru.mgp.util.Region box, org.bukkit.block.Biome biome) {
        int minY = Math.max(w.getMinHeight(), box.minY()) & ~3, maxY = Math.min(w.getMaxHeight() - 1, box.maxY());
        java.util.List<CompletableFuture<?>> all = new java.util.ArrayList<>();
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                final int fcx = cx, fcz = cz;
                all.add(w.getChunkAtAsync(cx, cz, false).thenAccept(c -> {
                    if (c == null) return;
                    for (int x = fcx * 16; x < fcx * 16 + 16; x += 4)
                        for (int z = fcz * 16; z < fcz * 16 + 16; z += 4)
                            for (int y = minY; y <= maxY; y += 4) w.setBiome(x, y, z, biome);
                }));
            }
        }
        return CompletableFuture.allOf(all.toArray(new CompletableFuture[0]));
    }

    /** Выгружает мир без сохранения и удаляет его папку. */
    public void destroy(World world) {
        if (world == null) return;
        for (Player p : world.getPlayers()) plugin.hub().send(p);
        Path dir = world.getWorldFolder().toPath();
        if (!Bukkit.unloadWorld(world, false)) {
            plugin.getLogger().warning("Не удалось выгрузить мир " + world.getName() + " (папка будет удалена при перезапуске)");
            return;
        }
        if (shuttingDown) {
            delete(dir);
        } else {
            // Небольшая задержка: даём серверу закрыть файлы регионов.
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> delete(dir), 40L);
        }
    }

    private void delete(Path dir) {
        try {
            FileUtil.deleteRecursive(dir);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Не удалось удалить " + dir, e);
        }
    }

    /**
     * Папка мира с ключом minecraft:<имя>. С версии 26.1 все миры хранятся как измерения основного:
     * world/dimensions/minecraft/<имя>/{region,entities,poi,data}.
     */
    public static Path dimensionDir(String worldName) {
        return Bukkit.getServer().getLevelDirectory().resolve("dimensions").resolve("minecraft")
                .resolve(worldName.toLowerCase(java.util.Locale.ROOT));
    }

    /** Удаляет копии миров, оставшиеся после падения/рестарта сервера (и папки в корне сервера от версий до 0.3.4). */
    public void cleanupLeftovers() {
        java.util.List<File> dirs = new java.util.ArrayList<>();
        File[] a = Bukkit.getWorldContainer().listFiles(f -> f.isDirectory() && f.getName().startsWith(PREFIX));
        File[] b = dimensionDir(PREFIX + "x").getParent().toFile().listFiles(f -> f.isDirectory() && f.getName().startsWith(PREFIX));
        if (a != null) dirs.addAll(java.util.List.of(a));
        if (b != null) dirs.addAll(java.util.List.of(b));
        for (File d : dirs) {
            World loaded = Bukkit.getWorld(d.getName());
            if (loaded != null && !Bukkit.unloadWorld(loaded, false)) continue; // при старте сессий ещё нет
            delete(d.toPath());
            plugin.getLogger().info("Удалён старый игровой мир " + d);
        }
    }

    /** Сохраняет мир и копирует его в templates/<template> (для /mgp setup export). */
    public CompletableFuture<Void> exportTemplate(World world, String template) {
        world.save();
        Path src = world.getWorldFolder().toPath();
        Path dst = templatesDir.resolve(template);
        CompletableFuture<Void> result = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                FileUtil.deleteRecursive(dst);
                FileUtil.copyWorld(src, dst);
                result.complete(null);
            } catch (IOException e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    public void setShuttingDown() { shuttingDown = true; }

    /** Id геймрула смены дня и ночи: в новых версиях advance_time, раньше do_daylight_cycle. */
    public static String daylightRule() {
        var reg = io.papermc.paper.registry.RegistryAccess.registryAccess().getRegistry(io.papermc.paper.registry.RegistryKey.GAME_RULE);
        for (String k : new String[]{"advance_time", "do_daylight_cycle"})
            if (reg.get(org.bukkit.NamespacedKey.minecraft(k)) != null) return k;
        return "advance_time";
    }

    /** Применяет геймрулы по их id (например show_death_messages, spawn_mobs — список: /gamerule в игре). */
    @SuppressWarnings("unchecked")
    public void applyGamerules(World world, Map<String, String> rules) {
        var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.GAME_RULE);
        for (Map.Entry<String, String> e : rules.entrySet()) {
            NamespacedKey key = NamespacedKey.fromString(e.getKey().toLowerCase(java.util.Locale.ROOT));
            GameRule<?> rule = key == null ? null : registry.get(key);
            if (rule == null) {
                plugin.getLogger().warning("Неизвестный gamerule: " + e.getKey());
                continue;
            }
            try {
                if (rule.getType() == Boolean.class) {
                    world.setGameRule((GameRule<Boolean>) rule, Boolean.parseBoolean(e.getValue()));
                } else if (rule.getType() == Integer.class) {
                    world.setGameRule((GameRule<Integer>) rule, Integer.parseInt(e.getValue()));
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("Неверное значение gamerule " + e.getKey() + ": " + e.getValue());
            }
        }
    }
}
