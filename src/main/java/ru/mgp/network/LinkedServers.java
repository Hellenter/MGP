package ru.mgp.network;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import ru.mgp.MGPPlugin;
import ru.mgp.util.Cfg;
import ru.mgp.util.Msg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Другие серверы сети (например, SMP с модом EAM). Игрок переходит туда ванильным пакетом transfer:
 * прокси не нужен, но у целевого сервера в server.properties должно быть accepts-transfers=true.
 */
public final class LinkedServers {
    /** Метка предмета в хотбаре: "server:<id>". */
    public static final String TAG_PREFIX = "server:";

    public record Server(String id, String name, Material icon, String host, int port,
                         List<String> description, int hotbarSlot) {
        public boolean configured() { return host != null && !host.isBlank(); }
    }

    private final MGPPlugin plugin;
    private Map<String, Server> servers = Map.of();

    public LinkedServers(MGPPlugin plugin) { this.plugin = plugin; }

    public void load() {
        Map<String, Server> loaded = new LinkedHashMap<>();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("servers");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(id);
                if (s == null || !s.getBoolean("enabled", true)) continue;
                String key = id.toLowerCase(Locale.ROOT);
                loaded.put(key, new Server(key,
                        s.getString("name", id),
                        Cfg.material(s.getString("icon"), Material.GRASS_BLOCK),
                        s.getString("host", "").trim(),
                        s.getInt("port", 25565),
                        new ArrayList<>(s.getStringList("description")),
                        s.getInt("hotbar-slot", -1)));
            }
        }
        servers = Collections.unmodifiableMap(loaded);
    }

    public List<Server> all() { return List.copyOf(servers.values()); }

    public Server get(String id) { return id == null ? null : servers.get(id.toLowerCase(Locale.ROOT)); }

    /** Отправить игрока на сервер. Из матча сначала надо выйти, чтобы не бросить игру посередине. */
    public void transfer(Player p, String id) {
        Server s = get(id);
        if (s == null) {
            Msg.send(p, "<red>Нет такого сервера: " + Msg.esc(id == null ? "" : id));
            return;
        }
        if (!s.configured()) {
            Msg.send(p, "<red>Сервер " + s.name() + "<red> ещё не настроен (servers." + s.id() + ".host в config.yml).");
            return;
        }
        if (plugin.sessions().of(p) != null) {
            Msg.send(p, "<red>Сначала выйди из игры: <yellow>/leave");
            return;
        }
        Msg.send(p, "<gray>Переходим на " + s.name() + "<gray>...");
        plugin.getLogger().info(p.getName() + " -> " + s.id() + " (" + s.host() + ":" + s.port() + ")");
        p.transfer(s.host(), s.port());
    }
}
