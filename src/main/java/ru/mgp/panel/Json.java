package ru.mgp.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.*;

/** Перевод YAML ↔ JSON для админ-панели. */
final class Json {
    private Json() {}

    /** Секция YAML → обычные Map/List (их понимает Gson). */
    static Object plain(Object v) {
        if (v instanceof ConfigurationSection s) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String k : s.getKeys(false)) m.put(k, plain(s.get(k)));
            return m;
        }
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> m = new LinkedHashMap<>();
            map.forEach((k, x) -> m.put(String.valueOf(k), plain(x)));
            return m;
        }
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object x : list) out.add(plain(x));
            return out;
        }
        return v;
    }

    /** JSON → Java-объекты; целые числа остаются целыми (а не 2.0). */
    static Object java(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonObject()) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) m.put(en.getKey(), java(en.getValue()));
            return m;
        }
        if (e.isJsonArray()) {
            List<Object> l = new ArrayList<>();
            for (JsonElement x : e.getAsJsonArray()) l.add(java(x));
            return l;
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isNumber()) {
            double d = p.getAsDouble();
            if (d == Math.rint(d) && Math.abs(d) < Integer.MAX_VALUE) return (int) d;
            return d;
        }
        return p.getAsString();
    }

    /** Заменяет содержимое YAML данными из JSON, сохраняя комментарии у верхних ключей. */
    @SuppressWarnings("unchecked")
    static void apply(YamlConfiguration y, JsonObject data) {
        Set<String> keep = data.keySet();
        for (String k : new ArrayList<>(y.getKeys(false))) if (!keep.contains(k)) y.set(k, null);
        for (Map.Entry<String, JsonElement> en : data.entrySet()) {
            Object v = java(en.getValue());
            if (v instanceof Map<?, ?> m) {
                // createSection на существующем ключе заменяет значение на том же месте — порядок разделов сохраняется
                y.createSection(en.getKey(), (Map<String, Object>) m);
            } else {
                y.set(en.getKey(), v);
            }
        }
    }

    static JsonObject obj(Object... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            String k = String.valueOf(kv[i]);
            if (v == null) o.add(k, null);
            else if (v instanceof JsonElement je) o.add(k, je);
            else if (v instanceof Number n) o.addProperty(k, n);
            else if (v instanceof Boolean b) o.addProperty(k, b);
            else o.addProperty(k, String.valueOf(v));
        }
        return o;
    }

    static JsonArray strings(Collection<String> list) {
        JsonArray a = new JsonArray();
        for (String s : list) a.add(s);
        return a;
    }
}
