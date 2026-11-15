package ru.mgp.editor;

import org.bukkit.configuration.file.YamlConfiguration;
import ru.mgp.MGPPlugin;
import ru.mgp.config.MapConfig;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

/**
 * Чтение и запись скриптов карты (maps/<карта>.yml → scripts) для палочки и команд.
 * Каждое изменение перечитывает файл, меняет список и сохраняет — правки из панели не теряются.
 */
public final class ScriptStore {
    private ScriptStore() {}

    /** Скрипты из уже загруженного файла карты (без чтения с диска) — для подсветки и меню. */
    public static List<Map<String, Object>> cached(MapConfig m) {
        return read(m.yaml);
    }

    public static List<Map<String, Object>> load(MapConfig m) {
        return read(YamlConfiguration.loadConfiguration(m.file));
    }

    private static List<Map<String, Object>> read(org.bukkit.configuration.ConfigurationSection y) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<?, ?> raw : y.getMapList("scripts")) {
            Map<String, Object> e = new LinkedHashMap<>();
            raw.forEach((k, v) -> e.put(String.valueOf(k), v));
            out.add(e);
        }
        return out;
    }

    public static Map<String, Object> find(List<Map<String, Object>> list, String id) {
        for (Map<String, Object> e : list) if (id.equals(String.valueOf(e.get("id")))) return e;
        return null;
    }

    public static Map<String, Object> findAt(List<Map<String, Object>> list, int x, int y, int z) {
        String want = x + " " + y + " " + z;
        for (Map<String, Object> e : list) {
            int[] b = block(e);
            if (b != null && b[0] == x && b[1] == y && b[2] == z) return e;
            if (want.equals(String.valueOf(e.get("block")))) return e;
        }
        return null;
    }

    /** Координаты блока скрипта или null. */
    public static int[] block(Map<String, Object> e) {
        try {
            String[] p = String.valueOf(e.get("block")).trim().split("[\\s,]+");
            return new int[]{(int) Math.floor(Double.parseDouble(p[0])), (int) Math.floor(Double.parseDouble(p[1])), (int) Math.floor(Double.parseDouble(p[2]))};
        } catch (Exception ex) {
            return null;
        }
    }

    public static List<String> actions(Map<String, Object> e) {
        List<String> out = new ArrayList<>();
        if (e.get("actions") instanceof List<?> l) for (Object o : l) out.add(String.valueOf(o));
        return out;
    }

    public static String nextId(List<Map<String, Object>> list, String prefix) {
        for (int i = 1; ; i++) if (find(list, prefix + i) == null) return prefix + i;
    }

    /**
     * Изменить скрипты карты и сохранить. fn получает весь список (можно добавлять и удалять).
     * Возвращает false, если карта не найдена или файл не сохранился.
     */
    public static boolean edit(MGPPlugin plugin, String mapId, Consumer<List<Map<String, Object>>> fn) {
        MapConfig m = plugin.registry().map(mapId);
        if (m == null) return false;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(m.file);
        List<Map<String, Object>> list = load(m);
        fn.accept(list);
        y.set("scripts", list.isEmpty() ? null : list);
        try {
            y.save(m.file);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить " + m.file + ": " + e.getMessage());
            return false;
        }
        plugin.registry().loadMap(m.file);
        return true;
    }

    /** Изменить один скрипт по id. */
    public static boolean editOne(MGPPlugin plugin, String mapId, String id, Consumer<Map<String, Object>> fn) {
        boolean[] found = {false};
        boolean ok = edit(plugin, mapId, list -> {
            Map<String, Object> e = find(list, id);
            if (e != null) {
                found[0] = true;
                fn.accept(e);
            }
        });
        return ok && found[0];
    }

    public static double num(Map<String, Object> e, String key, double def) {
        Object o = e.get(key);
        if (o instanceof Number n) return n.doubleValue();
        try {
            return o == null ? def : Double.parseDouble(String.valueOf(o).replace(',', '.'));
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    public static String str(Map<String, Object> e, String key, String def) {
        Object o = e.get(key);
        return o == null ? def : String.valueOf(o);
    }
}
