package ru.mgp.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.mgp.MGPPlugin;
import ru.mgp.config.MapConfig;
import ru.mgp.config.ModeConfig;
import ru.mgp.session.GameSession;
import ru.mgp.util.Msg;

import java.util.Locale;

/**
 * API для внешней админ-панели (окно в MC Server Manager): подробный список игроков
 * и действия над ними. Всё вызывается в основном потоке сервера.
 */
final class PanelActions {
    private final MGPPlugin plugin;

    PanelActions(MGPPlugin plugin) { this.plugin = plugin; }

    static String plain(String mm) {
        if (mm == null) return null;
        return PlainTextComponentSerializer.plainText().serialize(Msg.parse(mm));
    }

    /** GET /api/players — все игроки онлайн с местом, где они сейчас. */
    JsonArray players() {
        JsonArray arr = new JsonArray();
        for (Player p : Bukkit.getOnlinePlayers()) {
            GameSession s = plugin.sessions().of(p);
            JsonObject o = Json.obj(
                    "name", p.getName(),
                    "uuid", p.getUniqueId().toString(),
                    "world", p.getWorld().getName(),
                    "gamemode", p.getGameMode().name(),
                    "health", Math.round(p.getHealth() * 10) / 10.0,
                    "ping", p.getPing(),
                    "op", p.isOp(),
                    "session", s == null ? null : s.id,
                    "mode", s == null ? null : s.mode.id,
                    "modeName", s == null ? null : plain(s.mode.displayName),
                    "state", s == null ? null : s.state().name(),
                    "alive", s != null && s.isAlive(p),
                    "kills", s == null ? 0 : s.kills(p.getUniqueId()));
            arr.add(o);
        }
        return arr;
    }

    /** GET /api/admin — всё для окна менеджера одним запросом. */
    JsonObject snapshot() {
        JsonObject o = new JsonObject();
        o.addProperty("plugin", plugin.getPluginMeta().getVersion());
        o.addProperty("server", Bukkit.getName() + " " + Bukkit.getMinecraftVersion());
        o.addProperty("tps", Math.min(20.0, Math.round(Bukkit.getTPS()[0] * 10) / 10.0));
        o.addProperty("maxPlayers", Bukkit.getMaxPlayers());
        o.add("players", players());
        JsonArray sessions = new JsonArray();
        for (GameSession s : plugin.sessions().sessions()) {
            JsonArray names = new JsonArray();
            for (Player pl : s.online()) names.add(pl.getName());
            MapConfig m = s.map();
            JsonObject so = Json.obj("id", s.id, "mode", s.mode.id, "modeName", plain(s.mode.displayName),
                    "state", s.state().name(), "map", m == null ? null : m.id, "mapName", m == null ? null : plain(m.displayName),
                    "alive", s.aliveIds().size(), "size", s.size(), "min", s.minPlayers(), "max", s.mode.maxPlayers,
                    "elapsed", s.elapsed());
            so.add("players", names);
            sessions.add(so);
        }
        o.add("sessions", sessions);
        JsonArray modes = new JsonArray();
        for (ModeConfig mc : plugin.registry().modes()) {
            int ready = 0;
            var maps = plugin.registry().mapsFor(mc.id);
            for (MapConfig m : maps) if (plugin.sessions().playable(m)) ready++;
            modes.add(Json.obj("id", mc.id, "name", plain(mc.displayName), "type", mc.type,
                    "players", plugin.sessions().playersIn(mc.id), "maps", maps.size(), "readyMaps", ready,
                    "min", mc.minPlayers, "max", mc.maxPlayers));
        }
        o.add("modes", modes);
        JsonArray maps = new JsonArray();
        for (MapConfig m : plugin.registry().maps()) {
            boolean template = plugin.worlds().templateExists(m.template);
            maps.add(Json.obj("id", m.id, "name", plain(m.displayName), "mode", m.mode, "template", template,
                    "ready", plugin.sessions().playable(m)));
        }
        o.add("maps", maps);
        return o;
    }

    /** POST /api/players/&lt;name&gt;/&lt;hub|kick|join|message&gt;. */
    JsonElement player(String name, String action, JsonObject body) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) throw new IllegalArgumentException("Игрок не в сети: " + name);
        switch (action.toLowerCase(Locale.ROOT)) {
            case "hub" -> {
                plugin.sessions().leave(p);
                Msg.send(p, "<gray>Администратор отправил тебя в хаб.");
            }
            case "kick" -> {
                String reason = text(body, "reason");
                p.kick(Msg.parse(reason.isBlank() ? "<red>Кикнут администратором" : Msg.esc(reason)));
            }
            case "join" -> {
                String mode = text(body, "mode");
                if (plugin.registry().mode(mode) == null) throw new IllegalArgumentException("Режим не найден: " + mode);
                plugin.sessions().join(p, mode);
            }
            case "message" -> {
                String t = text(body, "text");
                if (t.isBlank()) throw new IllegalArgumentException("Пустое сообщение");
                Msg.send(p, "<gold>[Админ] <white>" + Msg.esc(t));
            }
            default -> throw new IllegalArgumentException("Неизвестное действие: " + action);
        }
        return Json.obj("ok", true);
    }

    /** POST /api/broadcast {text} — сообщение всем игрокам. */
    JsonElement broadcast(JsonObject body) {
        String t = text(body, "text");
        if (t.isBlank()) throw new IllegalArgumentException("Пустое сообщение");
        for (Player p : Bukkit.getOnlinePlayers()) Msg.send(p, "<gold>[Объявление] <white>" + Msg.esc(t));
        plugin.getLogger().info("[Объявление] " + t);
        return Json.obj("ok", true);
    }

    private static String text(JsonObject b, String k) {
        if (b == null || !b.has(k) || b.get(k).isJsonNull()) return "";
        return b.get(k).getAsString().trim();
    }
}
