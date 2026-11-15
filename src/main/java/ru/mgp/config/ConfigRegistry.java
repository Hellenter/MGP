package ru.mgp.config;

import org.bukkit.configuration.file.YamlConfiguration;
import ru.mgp.MGPPlugin;

import java.io.File;
import java.util.*;
import java.util.logging.Level;

/** Загружает все режимы (modes/*.yml) и карты (maps/*.yml). */
public final class ConfigRegistry {
    private final MGPPlugin plugin;
    private final Map<String, ModeConfig> modes = new LinkedHashMap<>();
    private final Map<String, MapConfig> maps = new LinkedHashMap<>();

    public ConfigRegistry(MGPPlugin plugin) { this.plugin = plugin; }

    public void load() {
        modes.clear();
        maps.clear();
        for (File f : files("modes")) {
            String id = id(f);
            try {
                modes.put(id, new ModeConfig(id, f, YamlConfiguration.loadConfiguration(f)));
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка в режиме " + f.getName() + ": " + e.getMessage(), e);
            }
        }
        for (File f : files("maps")) loadMap(f);
        plugin.getLogger().info("Загружено режимов: " + modes.size() + ", карт: " + maps.size());
    }

    public MapConfig loadMap(File f) {
        String id = id(f);
        try {
            MapConfig m = new MapConfig(id, f, YamlConfiguration.loadConfiguration(f));
            maps.put(id, m);
            return m;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Ошибка в карте " + f.getName() + ": " + e.getMessage(), e);
            return null;
        }
    }

    public ModeConfig reloadMode(String id) {
        File f = modeFile(id);
        ModeConfig m = new ModeConfig(id, f, YamlConfiguration.loadConfiguration(f));
        modes.put(id, m);
        return m;
    }

    private List<File> files(String dir) {
        File d = new File(plugin.getDataFolder(), dir);
        File[] list = d.listFiles((x, name) -> name.endsWith(".yml"));
        if (list == null) return List.of();
        Arrays.sort(list);
        return List.of(list);
    }

    private static String id(File f) {
        return f.getName().substring(0, f.getName().length() - 4).toLowerCase(Locale.ROOT);
    }

    public Collection<ModeConfig> modes() { return modes.values(); }
    public ModeConfig mode(String id) { return id == null ? null : modes.get(cleanId(id)); }
    public MapConfig map(String id) { return id == null ? null : maps.get(cleanId(id)); }

    /** Нижний регистр и без случайной пунктуации по краям: "hg1." или "«hg1»" из скопированной подсказки -> "hg1". */
    public static String cleanId(String id) {
        return id.toLowerCase(Locale.ROOT).replaceAll("^[^\\p{L}\\p{N}_-]+|[^\\p{L}\\p{N}_-]+$", "");
    }

    /** Текст ошибки "карта не найдена" со списком существующих карт. */
    public String mapNotFound(String id) {
        String known = maps.isEmpty() ? "карт пока нет" : "есть: " + String.join(", ", maps.keySet());
        return "Нет карты «" + id + "» (" + known + ")";
    }
    public Collection<MapConfig> maps() { return maps.values(); }

    public List<MapConfig> mapsFor(String modeId) {
        List<MapConfig> out = new ArrayList<>();
        for (MapConfig m : maps.values()) if (m.playableIn(modeId)) out.add(m);
        return out;
    }

    public File mapFile(String id) { return new File(plugin.getDataFolder(), "maps/" + id.toLowerCase(Locale.ROOT) + ".yml"); }
    public File modeFile(String id) { return new File(plugin.getDataFolder(), "modes/" + id.toLowerCase(Locale.ROOT) + ".yml"); }
}
