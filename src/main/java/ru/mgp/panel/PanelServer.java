package ru.mgp.panel;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ru.mgp.MGPPlugin;
import ru.mgp.config.MapConfig;
import ru.mgp.config.ModeConfig;
import ru.mgp.logic.GameLogics;
import ru.mgp.session.GameSession;
import ru.mgp.util.Pos;
import ru.mgp.util.Region;
import ru.mgp.vote.VoteType;
import ru.mgp.world.MapImporter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Админ-панель: маленький веб-сервер внутри плагина.
 * Отдаёт интерфейс (resources/web) и JSON API для редактирования режимов, карт и config.yml.
 * Доступ по токену. По умолчанию слушает только 127.0.0.1.
 */
public final class PanelServer {
    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,32}");
    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8", "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8", "json", "application/json; charset=utf-8",
            "svg", "image/svg+xml", "webmanifest", "application/manifest+json");

    private final MGPPlugin plugin;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private HttpServer server;
    private ExecutorService pool;
    private String token;

    public PanelServer(MGPPlugin plugin) { this.plugin = plugin; }

    // ------------------------------------------------------------------ запуск

    public void start() {
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("panel.enabled", true)) return;
        token = cfg.getString("panel.token", "");
        if (token == null || token.length() < 16) {
            byte[] b = new byte[24];
            new SecureRandom().nextBytes(b);
            token = HexFormat.of().formatHex(b);
            cfg.set("panel.token", token);
            plugin.saveConfig();
        }
        String host = cfg.getString("panel.host", "127.0.0.1");
        int port = cfg.getInt("panel.port", 8765);
        try {
            server = HttpServer.create(new InetSocketAddress(host, port), 0);
            server.createContext("/", this::handle);
            pool = Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "MGP-Panel");
                t.setDaemon(true);
                return t;
            });
            server.setExecutor(pool);
            server.start();
            plugin.getLogger().info("Админ-панель: " + url(false) + "  (ссылка с ключом: /mgp panel)");
            if (!host.equals("127.0.0.1") && !host.equals("localhost"))
                plugin.getLogger().warning("Панель доступна извне (" + host + "). Не передавай никому ссылку с ключом!");
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось запустить админ-панель на порту " + port, e);
        }
    }

    public void stop() {
        if (server != null) server.stop(0);
        if (pool != null) pool.shutdownNow();
        server = null;
    }

    public boolean running() { return server != null; }

    public String url(boolean withToken) {
        var cfg = plugin.getConfig();
        String base = cfg.getString("panel.public-url", "");
        if (base == null || base.isBlank()) {
            String host = cfg.getString("panel.host", "127.0.0.1");
            if (host.equals("0.0.0.0")) host = "localhost";
            base = "http://" + host + ":" + cfg.getInt("panel.port", 8765);
        }
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return withToken ? base + "/#token=" + token : base + "/";
    }

    // ------------------------------------------------------------------ HTTP

    private static final class ApiException extends RuntimeException {
        final int status;
        ApiException(int status, String msg) { super(msg); this.status = status; }
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (!path.startsWith("/api/")) {
                serveStatic(ex, path);
                return;
            }
            if (!authorized(ex)) {
                Thread.sleep(400); // замедляем перебор ключа
                send(ex, 401, Json.obj("error", "Неверный ключ доступа"));
                return;
            }
            String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
            String[] p = path.substring(5).split("/");
            JsonObject body = null;
            if (method.equals("POST") || method.equals("PUT")) {
                String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                body = raw.isBlank() ? new JsonObject() : JsonParser.parseString(raw).getAsJsonObject();
            }
            final JsonObject b = body;
            if (p[0].equals("import")) {
                send(ex, 200, importMap(b));
                return;
            }
            JsonElement result = sync(() -> route(method, p, b));
            send(ex, 200, result);
        } catch (ApiException e) {
            send(ex, e.status, Json.obj("error", e.getMessage()));
        } catch (JsonParseException | IllegalStateException e) {
            send(ex, 400, Json.obj("error", "Неверный JSON: " + e.getMessage()));
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Ошибка админ-панели", e);
            send(ex, 500, Json.obj("error", String.valueOf(e.getMessage())));
        } finally {
            ex.close();
        }
    }

    private boolean authorized(HttpExchange ex) {
        String t = ex.getRequestHeaders().getFirst("X-Token");
        return t != null && MessageDigest.isEqual(t.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    /** Всё, что трогает Bukkit или конфиги, выполняем в основном потоке сервера. */
    private JsonElement sync(Callable<JsonElement> task) throws Exception {
        Future<JsonElement> f = Bukkit.getScheduler().callSyncMethod(plugin, task);
        try {
            return f.get(15, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception c) throw c;
            throw e;
        }
    }

    private void serveStatic(HttpExchange ex, String path) throws IOException {
        if (path.equals("/") || path.isEmpty()) path = "/index.html";
        String name = path.substring(1);
        if (name.contains("..") || !name.matches("[a-zA-Z0-9_./-]+")) { sendRaw(ex, 404, "text/plain", "Not found".getBytes()); return; }
        try (InputStream in = plugin.getResource("web/" + name)) {
            if (in == null) { sendRaw(ex, 404, "text/plain", "Not found".getBytes()); return; }
            String ext = name.substring(name.lastIndexOf('.') + 1);
            sendRaw(ex, 200, TYPES.getOrDefault(ext, "application/octet-stream"), in.readAllBytes());
        }
    }

    private void send(HttpExchange ex, int status, JsonElement json) throws IOException {
        sendRaw(ex, status, "application/json; charset=utf-8", gson.toJson(json).getBytes(StandardCharsets.UTF_8));
    }

    private static void sendRaw(HttpExchange ex, int status, String type, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(data); }
    }

    // ------------------------------------------------------------------ API

    private JsonElement route(String m, String[] p, JsonObject body) throws Exception {
        String a = p[0];
        switch (a) {
            case "ping": return Json.obj("ok", true);
            case "overview": return overview();
            case "meta": return meta();
            case "reload":
                plugin.reloadAll();
                return Json.obj("ok", true);
            case "sessions": {
                need(p.length == 3 && m.equals("POST"), "Ожидалось POST /api/sessions/<id>/start|close");
                GameSession s = plugin.sessions().sessions().stream()
                        .filter(x -> String.valueOf(x.id).equals(p[1])).findFirst()
                        .orElseThrow(() -> new ApiException(404, "Сессия не найдена"));
                if (p[2].equals("start")) need(s.forceStart(), "Сессию нельзя запустить сейчас");
                else if (p[2].equals("close")) s.close();
                else throw new ApiException(404, "Неизвестное действие");
                return Json.obj("ok", true);
            }
            case "modes":
            case "maps": {
                String kind = a.equals("modes") ? "mode" : "map";
                if (p.length == 1 && m.equals("GET")) return list(kind);
                if (p.length == 1 && m.equals("POST")) return create(kind, body);
                String id = id(p[1]);
                File f = file(kind, id);
                if (kind.equals("map") && p.length == 3 && p[2].equals("rename") && m.equals("POST")) {
                    need(body != null, "Нет данных");
                    String name = body.has("name") && !body.get("name").isJsonNull() ? body.get("name").getAsString() : null;
                    try {
                        String msg = ru.mgp.world.MapRenamer.rename(plugin, id, str(body, "to"), name);
                        return Json.obj("ok", true, "map", plugin.registry().map(str(body, "to")).id, "message", msg);
                    } catch (IllegalArgumentException | IllegalStateException e) {
                        throw new ApiException(400, e.getMessage());
                    }
                }
                switch (m) {
                    case "GET": return read(f);
                    case "PUT": return save(kind, id, f, body);
                    case "DELETE":
                        need(f.exists(), "Файл не найден");
                        Files.delete(f.toPath());
                        plugin.registry().load();
                        return Json.obj("ok", true);
                    default: throw new ApiException(405, "Метод не поддерживается");
                }
            }
            case "config": {
                File f = new File(plugin.getDataFolder(), "config.yml");
                if (m.equals("GET")) {
                    JsonObject o = read(f).getAsJsonObject();
                    if (o.has("panel") && o.get("panel").isJsonObject()) o.getAsJsonObject("panel").addProperty("token", "********");
                    return o;
                }
                // токен не меняем через панель
                if (body.has("panel") && body.get("panel").isJsonObject())
                    body.getAsJsonObject("panel").addProperty("token", token);
                return save("config", "config", f, body);
            }
            case "raw": {
                need(p.length == 3, "Ожидалось /api/raw/<mode|map|config>/<id>");
                String kind = p[1];
                File f = kind.equals("config") ? new File(plugin.getDataFolder(), "config.yml") : file(kind, id(p[2]));
                if (m.equals("GET")) {
                    need(f.exists(), "Файл не найден");
                    String text = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                    if (kind.equals("config")) text = text.replace(token, "********");
                    return Json.obj("text", text);
                }
                String text = body.get("text").getAsString();
                if (kind.equals("config")) text = text.replace("********", token);
                YamlConfiguration y = new YamlConfiguration();
                try {
                    y.loadFromString(text);
                } catch (InvalidConfigurationException e) {
                    throw new ApiException(400, "Ошибка YAML: " + e.getMessage());
                }
                validate(kind, kind.equals("config") ? "config" : p[2], f, y);
                Files.writeString(f.toPath(), text, StandardCharsets.UTF_8);
                afterSave(kind);
                return Json.obj("ok", true);
            }
            case "templates": return Json.strings(templates());
            default: throw new ApiException(404, "Неизвестный запрос: " + a);
        }
    }

    /** Импорт карты: запуск в основном потоке, ожидание результата — в потоке HTTP (вырезка может идти долго). */
    private JsonElement importMap(JsonObject b) throws Exception {
        need(b != null, "Нет данных");
        Callable<CompletableFuture<MapImporter.Result>> start = () -> {
            org.bukkit.World w = Bukkit.getWorld(str(b, "world"));
            if (w == null) throw new ApiException(400, "Мир не найден: " + str(b, "world"));
            Pos p1 = new Pos(num(b, "x1"), num(b, "y1"), num(b, "z1"), 0, 0);
            Pos p2 = new Pos(num(b, "x2"), num(b, "y2"), num(b, "z2"), 0, 0);
            String name = b.has("name") && !b.get("name").isJsonNull() && !b.get("name").getAsString().isBlank() ? b.get("name").getAsString() : null;
            boolean chests = !b.has("detectChests") || b.get("detectChests").getAsBoolean();
            int margin = b.has("margin") ? b.get("margin").getAsInt() : plugin.getConfig().getInt("import.margin-chunks", 1);
            boolean trim = b.has("optimize") ? b.get("optimize").getAsBoolean() : plugin.getConfig().getBoolean("import.optimize-borders", true);
            return plugin.importer().run(new MapImporter.Request(w, id(str(b, "map")), str(b, "mode").toLowerCase(Locale.ROOT),
                    Region.of(p1, p2), name, chests, Math.max(0, Math.min(4, margin)), false, trim));
        };
        CompletableFuture<MapImporter.Result> f;
        try {
            f = Bukkit.getScheduler().callSyncMethod(plugin, start).get(15, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception c) throw c;
            throw e;
        }
        try {
            MapImporter.Result r = f.get(10, TimeUnit.MINUTES);
            return Json.obj("ok", true, "map", r.mapId(), "chunks", r.chunks(), "bytes", r.bytes(), "chests", r.chests(),
                    "created", r.created(), "summary", r.summary(), "trimmed", r.trimmed());
        } catch (ExecutionException e) {
            throw new ApiException(400, String.valueOf(e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
        }
    }

    private static String str(JsonObject b, String k) {
        if (!b.has(k) || b.get(k).isJsonNull()) throw new ApiException(400, "Не заполнено поле " + k);
        return b.get(k).getAsString().trim();
    }

    private static double num(JsonObject b, String k) {
        try {
            return Double.parseDouble(str(b, k).replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new ApiException(400, "Поле " + k + " должно быть числом");
        }
    }

    private JsonElement overview() {
        JsonObject o = new JsonObject();
        o.addProperty("server", Bukkit.getName() + " " + Bukkit.getVersion());
        o.addProperty("tps", Math.min(20.0, Math.round(Bukkit.getTPS()[0] * 10) / 10.0));
        JsonArray online = new JsonArray();
        for (Player pl : Bukkit.getOnlinePlayers()) {
            GameSession s = plugin.sessions().of(pl);
            online.add(Json.obj("name", pl.getName(), "session", s == null ? null : s.id));
        }
        o.add("online", online);
        JsonArray sessions = new JsonArray();
        for (GameSession s : plugin.sessions().sessions()) {
            List<String> names = new ArrayList<>();
            for (Player pl : s.online()) names.add(pl.getName());
            JsonObject so = Json.obj("id", s.id, "mode", s.mode.id, "state", s.state().name(),
                    "map", s.map() == null ? null : s.map().id, "alive", s.aliveIds().size(),
                    "max", s.mode.maxPlayers, "elapsed", s.elapsed());
            so.add("players", Json.strings(names));
            sessions.add(so);
        }
        o.add("sessions", sessions);
        JsonArray modes = new JsonArray();
        for (ModeConfig mc : plugin.registry().modes())
            modes.add(Json.obj("id", mc.id, "name", mc.displayName, "players", plugin.sessions().playersIn(mc.id),
                    "maps", plugin.registry().mapsFor(mc.id).size()));
        o.add("modes", modes);
        return o;
    }

    private JsonElement meta() {
        JsonObject o = new JsonObject();
        List<String> mats = new ArrayList<>();
        for (Material mat : Material.values()) if (mat.isItem() && !mat.name().startsWith("LEGACY_")) mats.add(mat.name());
        o.add("materials", Json.strings(mats));
        o.add("modeTypes", Json.strings(GameLogics.TYPES));
        List<String> votes = new ArrayList<>();
        for (VoteType v : VoteType.values()) votes.add(v.name());
        o.add("voteTypes", Json.strings(votes));
        o.add("templates", Json.strings(templates()));
        o.add("modes", Json.strings(plugin.registry().modes().stream().map(x -> x.id).toList()));
        o.add("worlds", Json.strings(Bukkit.getWorlds().stream().map(org.bukkit.World::getName)
                .filter(n -> !n.startsWith(ru.mgp.world.WorldService.PREFIX)).toList()));
        var reg = RegistryAccess.registryAccess();
        o.add("effects", Json.strings(reg.getRegistry(RegistryKey.MOB_EFFECT).keyStream().map(NamespacedKey::getKey).sorted().toList()));
        o.add("enchantments", Json.strings(reg.getRegistry(RegistryKey.ENCHANTMENT).keyStream().map(NamespacedKey::getKey).sorted().toList()));
        o.add("professions", Json.strings(reg.getRegistry(RegistryKey.VILLAGER_PROFESSION).keyStream().map(NamespacedKey::getKey).sorted().toList()));
        o.add("gamerules", Json.strings(reg.getRegistry(RegistryKey.GAME_RULE).keyStream().map(NamespacedKey::getKey).sorted().toList()));
        return o;
    }

    private JsonElement list(String kind) {
        JsonArray arr = new JsonArray();
        if (kind.equals("mode")) {
            for (File f : files("modes")) {
                String id = idOf(f);
                ModeConfig mc = plugin.registry().mode(id);
                arr.add(Json.obj("id", id, "name", mc != null ? mc.displayName : id, "type", mc != null ? mc.type : "?", "valid", mc != null));
            }
        } else {
            for (File f : files("maps")) {
                String id = idOf(f);
                MapConfig mc = plugin.registry().map(id);
                arr.add(Json.obj("id", id, "name", mc != null ? mc.displayName : id, "mode", mc != null ? mc.mode : "?",
                        "valid", mc != null, "template", mc != null && plugin.worlds().templateExists(mc.template)));
            }
        }
        return arr;
    }

    private JsonElement create(String kind, JsonObject body) throws Exception {
        String id = id(body.get("id").getAsString());
        File f = file(kind, id);
        need(!f.exists(), "Такой файл уже существует");
        YamlConfiguration y;
        String copy = body.has("copyFrom") && !body.get("copyFrom").isJsonNull() ? body.get("copyFrom").getAsString() : "";
        if (!copy.isBlank()) {
            File src = file(kind, id(copy));
            need(src.exists(), "Нет файла для копирования");
            y = YamlConfiguration.loadConfiguration(src);
        } else if (kind.equals("mode")) {
            String type = body.has("type") ? body.get("type").getAsString() : "hunger_games";
            need(GameLogics.TYPES.contains(type), "Неизвестный тип режима");
            y = new YamlConfiguration();
            y.set("type", type);
            y.set("display-name", "<yellow>" + id);
            y.set("icon", "GRASS_BLOCK");
            y.set("min-players", 2);
            y.set("max-players", 8);
            y.set("waiting.template", "waiting_lobby");
            y.set("waiting.spawn", "0.5 65 0.5");
        } else {
            y = new YamlConfiguration();
            y.set("mode", body.has("mode") ? body.get("mode").getAsString() : "");
            y.set("display-name", "<yellow>" + id);
            y.set("icon", "FILLED_MAP");
            y.set("template", id);
            y.set("spawns", new ArrayList<String>());
        }
        f.getParentFile().mkdirs();
        y.save(f);
        plugin.registry().load();
        return Json.obj("ok", true, "id", id);
    }

    private JsonElement read(File f) {
        if (!f.exists()) throw new ApiException(404, "Файл не найден");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        return gson.toJsonTree(Json.plain(y));
    }

    private JsonElement save(String kind, String id, File f, JsonObject body) throws Exception {
        YamlConfiguration y = f.exists() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
        Json.apply(y, body);
        validate(kind, id, f, y);
        y.save(f);
        afterSave(kind);
        return Json.obj("ok", true);
    }

    /** Пробуем разобрать конфиг теми же классами, что и плагин, — ошибки покажем в панели и не сохраним файл. */
    private void validate(String kind, String id, File f, YamlConfiguration y) {
        try {
            switch (kind) {
                case "mode" -> {
                    ModeConfig mc = new ModeConfig(id, f, y);
                    need(GameLogics.TYPES.contains(mc.type), "Неизвестный тип режима: " + mc.type);
                }
                case "map" -> new MapConfig(id, f, y);
                default -> {}
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(400, "Ошибка в настройках: " + e.getMessage());
        }
    }

    private void afterSave(String kind) {
        if (kind.equals("config")) plugin.reloadAll();
        else plugin.registry().load();
    }

    // ------------------------------------------------------------------ helpers

    private File file(String kind, String id) {
        return switch (kind) {
            case "mode" -> plugin.registry().modeFile(id);
            case "map" -> plugin.registry().mapFile(id);
            default -> throw new ApiException(404, "Неизвестный тип: " + kind);
        };
    }

    private List<File> files(String dir) {
        File[] l = new File(plugin.getDataFolder(), dir).listFiles((d, n) -> n.endsWith(".yml"));
        if (l == null) return List.of();
        Arrays.sort(l);
        return List.of(l);
    }

    private List<String> templates() {
        File[] l = plugin.worlds().templatesDir().toFile().listFiles(File::isDirectory);
        if (l == null) return List.of();
        return Arrays.stream(l).map(File::getName).sorted().toList();
    }

    private static String idOf(File f) { return f.getName().substring(0, f.getName().length() - 4).toLowerCase(Locale.ROOT); }

    private static String id(String raw) {
        String id = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!ID.matcher(id).matches()) throw new ApiException(400, "Id может содержать только a-z, 0-9 и _ (до 32 символов)");
        return id;
    }

    private static void need(boolean cond, String msg) {
        if (!cond) throw new ApiException(400, msg);
    }
}
