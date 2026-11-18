package ru.mgp.stats;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import ru.mgp.MGPPlugin;
import ru.mgp.config.ModeConfig;
import ru.mgp.session.GameSession;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.logging.Level;

/**
 * Статистика игроков по режимам: сыграно, победы, убийства, смерти (K/D считается из них).
 * Хранится в plugins/MGP/stats.yml; запись — раз в минуту в фоне и при выключении сервера.
 * «Сыграно» засчитывается на старте игры, поэтому выход посреди игры — это сыгранная игра без победы.
 */
public final class StatsService implements Listener {
    /** Одна строка топа. */
    public record Row(UUID uuid, String name, double value) {}

    /** Всё, что известно об игроке. */
    public static final class PlayerStats {
        public final UUID uuid;
        public String name;
        public long lastPlayed;
        public final Map<String, Counts> modes = new LinkedHashMap<>();

        PlayerStats(UUID uuid, String name) { this.uuid = uuid; this.name = name; }

        /** Счётчики режима; null или "all" — сумма по всем режимам. */
        public Counts counts(String mode) {
            if (mode == null || mode.equals(ALL)) {
                Counts sum = new Counts();
                modes.values().forEach(sum::add);
                return sum;
            }
            Counts c = modes.get(mode);
            return c != null ? c : new Counts();
        }

        Counts mode(String mode) { return modes.computeIfAbsent(mode, k -> new Counts()); }
    }

    public static final String ALL = "all";

    private final MGPPlugin plugin;
    private final File file;
    private final Map<UUID, PlayerStats> players = new HashMap<>();
    private boolean dirty;
    private int saveTask = -1;
    /** Сохранения идут по одному: следующее ждёт предыдущее. */
    private final Object writeLock = new Object();

    public StatsService(MGPPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "stats.yml");
    }

    public boolean enabled() { return plugin.getConfig().getBoolean("stats.enabled", true); }

    /** Сколько игр нужно сыграть, чтобы попасть в топ по K/D (чтобы 1 убийство без смертей не было «лучшим»). */
    public int kdMinGames() { return Math.max(0, plugin.getConfig().getInt("stats.kd-min-games", 5)); }

    // ------------------------------------------------------------------ хранение

    public void load() {
        players.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String k : y.getKeys(false)) {
            UUID u;
            try { u = UUID.fromString(k); } catch (IllegalArgumentException e) { continue; }
            ConfigurationSection s = y.getConfigurationSection(k);
            if (s == null) continue;
            PlayerStats ps = new PlayerStats(u, s.getString("name", k.substring(0, 8)));
            ps.lastPlayed = s.getLong("last-played", 0);
            ConfigurationSection modes = s.getConfigurationSection("modes");
            if (modes != null) for (String m : modes.getKeys(false)) {
                ConfigurationSection c = modes.getConfigurationSection(m);
                if (c == null) continue;
                Counts cnt = ps.mode(m);
                cnt.played = c.getInt("played");
                cnt.wins = c.getInt("wins");
                cnt.kills = c.getInt("kills");
                cnt.deaths = c.getInt("deaths");
            }
            players.put(u, ps);
        }
        plugin.getLogger().info("Статистика: " + players.size() + " игроков");
    }

    public void start() {
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> { if (dirty) save(true); }, 1200L, 1200L).getTaskId();
    }

    public void shutdown() {
        if (saveTask != -1) Bukkit.getScheduler().cancelTask(saveTask);
        saveTask = -1;
        if (dirty) save(false);
    }

    /** Снимок делается в основном потоке, на диск пишется в фоне (async) или сразу. */
    private void save(boolean async) {
        YamlConfiguration y = new YamlConfiguration();
        for (PlayerStats ps : players.values()) {
            String k = ps.uuid.toString();
            y.set(k + ".name", ps.name);
            if (ps.lastPlayed > 0) y.set(k + ".last-played", ps.lastPlayed);
            for (var e : ps.modes.entrySet()) {
                Counts c = e.getValue();
                if (c.empty()) continue;
                String b = k + ".modes." + e.getKey();
                y.set(b + ".played", c.played);
                y.set(b + ".wins", c.wins);
                y.set(b + ".kills", c.kills);
                y.set(b + ".deaths", c.deaths);
            }
        }
        String data = y.saveToString();
        dirty = false;
        Runnable write = () -> {
            synchronized (writeLock) {
                try {
                    plugin.getDataFolder().mkdirs();
                    File tmp = new File(plugin.getDataFolder(), "stats.yml.tmp");
                    Files.writeString(tmp.toPath(), data, StandardCharsets.UTF_8);
                    // целиком или никак: при сбое посреди записи старый файл остаётся
                    try {
                        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (IOException e) {
                        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    plugin.getLogger().log(Level.WARNING, "Не удалось сохранить stats.yml", e);
                }
            }
        };
        if (async) Bukkit.getScheduler().runTaskAsynchronously(plugin, write);
        else write.run();
    }

    // ------------------------------------------------------------------ запись событий игры

    /** Считать ли этого игрока: статистика включена, это не тестовый бот (stats.count-bots). */
    private boolean tracked(UUID u) {
        if (!enabled()) return false;
        if (plugin.getConfig().getBoolean("stats.count-bots", false)) return true;
        return plugin.bots() == null || !plugin.bots().isBot(u);
    }

    private PlayerStats entry(UUID u) {
        Player p = Bukkit.getPlayer(u);
        PlayerStats ps = players.computeIfAbsent(u, k -> new PlayerStats(k, p != null ? p.getName() : k.toString().substring(0, 8)));
        if (p != null) ps.name = p.getName();
        dirty = true;
        return ps;
    }

    /** Игра началась: всем участникам +1 сыгранная. */
    public void gameStarted(GameSession s) {
        long now = System.currentTimeMillis();
        for (UUID u : s.aliveIds()) {
            if (!tracked(u)) continue;
            PlayerStats ps = entry(u);
            ps.mode(s.mode.id).played++;
            ps.lastPlayed = now;
        }
    }

    public void gameWon(GameSession s, Collection<UUID> winners) {
        for (UUID u : winners) if (tracked(u)) entry(u).mode(s.mode.id).wins++;
    }

    public void kill(GameSession s, UUID killer) {
        if (tracked(killer)) entry(killer).mode(s.mode.id).kills++;
    }

    public void death(GameSession s, UUID victim) {
        if (tracked(victim)) entry(victim).mode(s.mode.id).deaths++;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        PlayerStats ps = players.get(e.getPlayer().getUniqueId());
        if (ps != null && !ps.name.equals(e.getPlayer().getName())) {
            ps.name = e.getPlayer().getName(); // сменил ник — в топах новый
            dirty = true;
        }
    }

    // ------------------------------------------------------------------ чтение

    public PlayerStats get(UUID u) { return players.get(u); }

    /** По нику (без учёта регистра) или UUID. */
    public PlayerStats find(String nameOrUuid) {
        if (nameOrUuid == null) return null;
        try { return players.get(UUID.fromString(nameOrUuid)); } catch (IllegalArgumentException ignored) {}
        PlayerStats best = null;
        for (PlayerStats ps : players.values())
            if (ps.name.equalsIgnoreCase(nameOrUuid) && (best == null || ps.lastPlayed > best.lastPlayed)) best = ps;
        return best;
    }

    public Collection<PlayerStats> all() { return Collections.unmodifiableCollection(players.values()); }

    /** Режимы, по которым есть хоть какая-то статистика, плюс все загруженные режимы. */
    public List<String> modeIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (ModeConfig m : plugin.registry().modes()) ids.add(m.id);
        for (PlayerStats ps : players.values()) ids.addAll(ps.modes.keySet());
        return new ArrayList<>(ids);
    }

    /** Название режима для людей (если режим удалён — его id). */
    public String modeName(String mode) {
        if (mode == null || mode.equals(ALL)) return "Все режимы";
        ModeConfig m = plugin.registry().mode(mode);
        return m != null ? m.displayName : mode;
    }

    /** Топ по показателю в режиме (null / "all" — все режимы вместе). Нули в топ не попадают. */
    public List<Row> top(Stat stat, String mode, int limit) {
        List<Row> rows = new ArrayList<>();
        int minGames = kdMinGames();
        for (PlayerStats ps : players.values()) {
            Counts c = ps.counts(mode);
            if (stat == Stat.KD && c.played < minGames) continue;
            double v = c.get(stat);
            if (v <= 0) continue;
            rows.add(new Row(ps.uuid, ps.name, v));
        }
        rows.sort(Comparator.comparingDouble(Row::value).reversed().thenComparing(Row::name, String.CASE_INSENSITIVE_ORDER));
        return rows.size() > limit ? new ArrayList<>(rows.subList(0, limit)) : rows;
    }

    /** Место игрока в топе (с 1); 0 — не в топе. */
    public int rank(UUID u, Stat stat, String mode) {
        List<Row> all = top(stat, mode, Integer.MAX_VALUE);
        for (int i = 0; i < all.size(); i++) if (all.get(i).uuid().equals(u)) return i + 1;
        return 0;
    }

    /** Обнулить игрока: весь (mode == null) или один режим. */
    public boolean reset(UUID u, String mode) {
        PlayerStats ps = players.get(u);
        if (ps == null) return false;
        if (mode == null || mode.equals(ALL)) players.remove(u);
        else if (ps.modes.remove(mode) == null) return false;
        dirty = true;
        return true;
    }

    // ------------------------------------------------------------------ JSON для админ-панели

    public JsonObject json(PlayerStats ps) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", ps.uuid.toString());
        o.addProperty("name", ps.name);
        o.addProperty("online", Bukkit.getPlayer(ps.uuid) != null);
        o.addProperty("lastPlayed", ps.lastPlayed);
        o.add("total", json(ps.counts(ALL)));
        JsonObject modes = new JsonObject();
        for (var e : ps.modes.entrySet()) if (!e.getValue().empty()) modes.add(e.getKey(), json(e.getValue()));
        o.add("modes", modes);
        return o;
    }

    public static JsonObject json(Counts c) {
        JsonObject o = new JsonObject();
        o.addProperty("played", c.played);
        o.addProperty("wins", c.wins);
        o.addProperty("losses", c.losses());
        o.addProperty("kills", c.kills);
        o.addProperty("deaths", c.deaths);
        o.addProperty("kd", Math.round(c.kd() * 100) / 100.0);
        o.addProperty("winRate", c.winRate());
        return o;
    }

    public JsonArray json(List<Row> rows, Stat stat) {
        JsonArray a = new JsonArray();
        int i = 1;
        for (Row r : rows) {
            JsonObject o = new JsonObject();
            o.addProperty("rank", i++);
            o.addProperty("uuid", r.uuid().toString());
            o.addProperty("name", r.name());
            if (stat == Stat.KD) o.addProperty("value", Math.round(r.value() * 100) / 100.0);
            else o.addProperty("value", (long) r.value());
            a.add(o);
        }
        return a;
    }

    /** Список режимов с названиями для панели. */
    public JsonArray modesJson() {
        JsonArray a = new JsonArray();
        for (String id : modeIds()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("name", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(ru.mgp.util.Msg.parse(modeName(id))));
            a.add(o);
        }
        return a;
    }
}
