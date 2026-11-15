package ru.mgp.util;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.*;

/** Мелкие помощники для чтения YAML. */
public final class Cfg {
    private Cfg() {}

    public static Material material(String name, Material def) {
        if (name == null) return def;
        Material m = Material.matchMaterial(name.trim());
        return m == null ? def : m;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        if (o instanceof ConfigurationSection s) return s.getValues(false);
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return null;
    }

    public static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : String.valueOf(v);
    }

    public static int integer(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v != null) try { return Integer.parseInt(String.valueOf(v).trim()); } catch (NumberFormatException ignored) {}
        return def;
    }

    public static double dbl(Map<String, Object> m, String key, double def) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v != null) try { return Double.parseDouble(String.valueOf(v).trim()); } catch (NumberFormatException ignored) {}
        return def;
    }

    public static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v instanceof Boolean b) return b;
        return v == null ? def : Boolean.parseBoolean(String.valueOf(v));
    }

    public static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) out.add(String.valueOf(x));
        else if (o != null) out.add(String.valueOf(o));
        return out;
    }

    /** "5:00", "300" или 300 → секунды. */
    public static int seconds(Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number n) return n.intValue();
        String s = String.valueOf(o).trim();
        try {
            if (s.contains(":")) {
                String[] p = s.split(":");
                return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
            }
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** "2-5" или 3 → {min, max}. */
    public static int[] range(Object o, int defMin, int defMax) {
        if (o == null) return new int[]{defMin, defMax};
        if (o instanceof Number n) return new int[]{n.intValue(), n.intValue()};
        String s = String.valueOf(o).trim();
        try {
            if (s.contains("-")) {
                String[] p = s.split("-");
                int a = Integer.parseInt(p[0].trim()), b = Integer.parseInt(p[1].trim());
                return new int[]{Math.min(a, b), Math.max(a, b)};
            }
            int v = Integer.parseInt(s);
            return new int[]{v, v};
        } catch (NumberFormatException e) {
            return new int[]{defMin, defMax};
        }
    }

    public static <E extends Enum<E>> E enumValue(Class<E> type, String name, E def) {
        if (name == null) return def;
        try {
            return Enum.valueOf(type, name.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
