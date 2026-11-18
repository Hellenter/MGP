package ru.mgp.stats;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import ru.mgp.MGPPlugin;
import ru.mgp.util.Msg;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Голограммы-топы в хабе (TextDisplay). Хранятся в plugins/MGP/leaderboards.yml.
 * Сущности не сохраняются в мир: при старте и после выгрузки чанка создаются заново.
 */
public final class Leaderboards {
    /** Одна голограмма. */
    public record Board(String id, String world, double x, double y, double z, Stat stat, String mode, int size) {
        Location location() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z);
        }
    }

    private final MGPPlugin plugin;
    private final StatsService stats;
    private final File file;
    private final org.bukkit.NamespacedKey key;
    private final Map<String, Board> boards = new LinkedHashMap<>();
    private final Map<String, TextDisplay> live = new HashMap<>();
    private int task = -1;
    private int ticks;

    public Leaderboards(MGPPlugin plugin, StatsService stats) {
        this.plugin = plugin;
        this.stats = stats;
        this.file = new File(plugin.getDataFolder(), "leaderboards.yml");
        this.key = new org.bukkit.NamespacedKey(plugin, "leaderboard");
    }

    public void load() {
        despawnAll();
        boards.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("boards");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Stat st = Stat.parse(s.getString("stat", "wins"));
            if (st == null) { plugin.getLogger().warning("leaderboards.yml: " + id + ": неизвестный показатель " + s.getString("stat")); continue; }
            boards.put(id, new Board(id, s.getString("world", "world"), s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                    st, s.getString("mode", StatsService.ALL), Math.max(1, Math.min(20, s.getInt("size", 10)))));
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Board b : boards.values()) {
            String k = "boards." + b.id();
            y.set(k + ".world", b.world());
            y.set(k + ".x", b.x());
            y.set(k + ".y", b.y());
            y.set(k + ".z", b.z());
            y.set(k + ".stat", b.stat().id);
            y.set(k + ".mode", b.mode());
            y.set(k + ".size", b.size());
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить leaderboards.yml: " + e.getMessage());
        }
    }

    /** Раз в секунду: вернуть пропавшие голограммы; текст обновляется раз в stats.leaderboard-refresh секунд. */
    public void start() {
        // старые голограммы, оставшиеся от прошлого запуска (например, после краша), убираем
        for (World w : Bukkit.getWorlds())
            for (TextDisplay d : w.getEntitiesByClass(TextDisplay.class))
                if (d.getPersistentDataContainer().has(key, PersistentDataType.STRING)) d.remove();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 20L).getTaskId();
    }

    public void shutdown() {
        if (task != -1) Bukkit.getScheduler().cancelTask(task);
        task = -1;
        despawnAll();
    }

    private void tick() {
        int refresh = Math.max(5, plugin.getConfig().getInt("stats.leaderboard-refresh", 30));
        boolean update = ticks++ % refresh == 0;
        for (Board b : boards.values()) {
            TextDisplay d = live.get(b.id());
            if (d == null || !d.isValid()) {
                d = spawn(b);
                if (d == null) continue;
                update = true;
            }
            if (update) d.text(Msg.parse(text(b)));
        }
    }

    /** Обновить тексты сейчас (например, после конца игры). */
    public void refresh() {
        for (Board b : boards.values()) {
            TextDisplay d = live.get(b.id());
            if (d != null && d.isValid()) d.text(Msg.parse(text(b)));
        }
    }

    private TextDisplay spawn(Board b) {
        Location l = b.location();
        if (l == null || !l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) return null;
        TextDisplay d = l.getWorld().spawn(l, TextDisplay.class, x -> {
            x.setPersistent(false);
            x.getPersistentDataContainer().set(key, PersistentDataType.STRING, b.id());
            x.setBillboard(Display.Billboard.VERTICAL);
            x.setShadowed(true);
            x.setDefaultBackground(false);
            x.setBackgroundColor(Color.fromARGB(0x60000000));
            x.setLineWidth(260);
            x.setAlignment(TextDisplay.TextAlignment.CENTER);
            x.text(Msg.parse(text(b)));
        });
        live.put(b.id(), d);
        return d;
    }

    private void despawnAll() {
        for (TextDisplay d : live.values()) if (d != null && d.isValid()) d.remove();
        live.clear();
    }

    /** Текст голограммы (MiniMessage). */
    public String text(Board b) {
        StringBuilder sb = new StringBuilder();
        sb.append("<gold><bold>Топ по ").append(b.stat().byTitle).append("</bold></gold>\n");
        sb.append("<gray>").append(stats.modeName(b.mode())).append("</gray>\n");
        List<StatsService.Row> rows = stats.top(b.stat(), b.mode(), b.size());
        if (rows.isEmpty()) sb.append("\n<dark_gray>Пока никого — сыграй первым!");
        for (int i = 0; i < b.size(); i++) {
            sb.append("<reset>\n");
            String place = switch (i) {
                case 0 -> "<gold>";
                case 1 -> "<white>";
                case 2 -> "<#cd7f32>";
                default -> "<gray>";
            };
            if (i < rows.size()) {
                StatsService.Row r = rows.get(i);
                sb.append(place).append(i + 1).append(". <white>").append(Msg.esc(r.name()))
                        .append(" <dark_gray>- <yellow>").append(b.stat().format(r.value()));
            } else if (!rows.isEmpty()) sb.append("<dark_gray>").append(i + 1).append(". ---");
        }
        if (b.stat() == Stat.KD && stats.kdMinGames() > 0)
            sb.append("\n\n<dark_gray>от ").append(stats.kdMinGames()).append(" игр");
        return sb.toString();
    }

    // ------------------------------------------------------------------ команды

    public Collection<Board> all() { return Collections.unmodifiableCollection(boards.values()); }

    public Board get(String id) { return boards.get(id); }

    /** Новая голограмма на месте игрока (на уровне глаз). Возвращает id. */
    public Board add(Location at, Stat stat, String mode, int size) {
        String base = stat.id + (mode == null || mode.equals(StatsService.ALL) ? "" : "_" + mode);
        String id = base;
        for (int n = 2; boards.containsKey(id); n++) id = base + "_" + n;
        Board b = new Board(id, at.getWorld().getName(), round(at.getX()), round(at.getY()), round(at.getZ()),
                stat, mode == null ? StatsService.ALL : mode, Math.max(1, Math.min(20, size)));
        boards.put(id, b);
        save();
        spawn(b);
        return b;
    }

    public boolean remove(String id) {
        Board b = boards.remove(id);
        if (b == null) return false;
        TextDisplay d = live.remove(id);
        if (d != null && d.isValid()) d.remove();
        save();
        return true;
    }

    public boolean move(String id, Location at) {
        Board b = boards.get(id);
        if (b == null) return false;
        remove(id);
        Board nb = new Board(id, at.getWorld().getName(), round(at.getX()), round(at.getY()), round(at.getZ()), b.stat(), b.mode(), b.size());
        boards.put(id, nb);
        save();
        spawn(nb);
        return true;
    }

    /** Ближайшая голограмма в пределах maxDist блоков от точки. */
    public Board nearest(Location at, double maxDist) {
        Board best = null;
        double bd = maxDist * maxDist;
        for (Board b : boards.values()) {
            if (!b.world().equals(at.getWorld().getName())) continue;
            double dx = b.x() - at.getX(), dy = b.y() - at.getY(), dz = b.z() - at.getZ();
            double d = dx * dx + dy * dy + dz * dz;
            if (d <= bd) { bd = d; best = b; }
        }
        return best;
    }

    /** Принадлежит ли сущность голограммам (чтобы другие системы её не трогали). */
    public boolean isBoard(Entity e) {
        return e instanceof TextDisplay && e.getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    private static double round(double v) { return Math.round(v * 100) / 100.0; }
}
