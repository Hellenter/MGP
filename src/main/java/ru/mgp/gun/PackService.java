package ru.mgp.gun;

import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import ru.mgp.MGPPlugin;
import ru.mgp.session.GameSession;
import ru.mgp.util.Msg;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/**
 * Ресурспак с моделями оружия (guns.yml → resource-pack): отправляется, пока игрок в сессии одного из режимов modes
 * (уже в лобби ожидания, чтобы успел скачаться), и снимается, когда он возвращается в хаб.
 */
public final class PackService implements Listener {
    private final MGPPlugin plugin;
    private boolean enabled;
    private String url = "";
    private String hash = "";
    private boolean required;
    private String prompt = "";
    private final Set<String> modes = new HashSet<>();
    private UUID packId;
    /** Кому пак отправлен (и не снят). */
    private final Set<UUID> sent = new HashSet<>();
    /** У кого пак загружен. */
    private final Set<UUID> loaded = new HashSet<>();
    private int hashJob;

    PackService(MGPPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::sync, 20L, 10L);
    }

    void load(ConfigurationSection c) {
        // снять старый пак у всех: адрес мог поменяться
        for (UUID u : new ArrayList<>(sent)) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && packId != null) p.removeResourcePacks(packId);
        }
        sent.clear();
        loaded.clear();
        enabled = c != null && c.getBoolean("enabled", true);
        url = c == null ? "" : c.getString("url", "").trim();
        hash = c == null ? "" : c.getString("hash", "").trim().toLowerCase(Locale.ROOT);
        required = c != null && c.getBoolean("required", false);
        prompt = c == null ? "" : c.getString("prompt", "");
        modes.clear();
        if (c != null) for (String m : c.getStringList("modes")) modes.add(m.toLowerCase(Locale.ROOT));
        if (url.isEmpty()) enabled = false;
        packId = UUID.nameUUIDFromBytes(("mgp-pack:" + url).getBytes(StandardCharsets.UTF_8));
        if (enabled && !hash.matches("[0-9a-f]{40}")) {
            hash = "";
            computeHash(url, ++hashJob);
        }
    }

    /** SHA-1 пака: клиент кэширует пак по хэшу и не качает его заново при каждом входе в режим. */
    private void computeHash(String u, int job) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS)
                        .connectTimeout(Duration.ofSeconds(15)).build();
                HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(URI.create(u)).timeout(Duration.ofSeconds(60)).GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode());
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                long size = 0;
                try (InputStream in = res.body()) {
                    byte[] buf = new byte[65536];
                    for (int n; (n = in.read(buf)) > 0; ) { md.update(buf, 0, n); size += n; }
                }
                String h = HexFormat.of().formatHex(md.digest());
                long kb = size / 1024;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (job != hashJob) return;
                    hash = h;
                    plugin.getLogger().info("Ресурспак оружия: " + kb + " КБ, SHA-1 " + h);
                });
            } catch (Exception e) {
                plugin.getLogger().warning("Ресурспак оружия: не удалось скачать для подсчёта SHA-1 (" + e.getMessage()
                        + "). Пак отправляется без хэша — клиент будет качать его каждый раз.");
            }
        });
    }

    public boolean loaded(Player p) { return loaded.contains(p.getUniqueId()); }

    private boolean wants(Player p) {
        if (!enabled) return false;
        GameSession s = plugin.sessions().of(p);
        return s != null && (modes.contains(s.mode.id.toLowerCase(Locale.ROOT)) || modes.contains(s.mode.type.toLowerCase(Locale.ROOT)));
    }

    private void sync() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            boolean want = wants(p);
            if (want && !sent.contains(u)) {
                sent.add(u);
                ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(packId, URI.create(url), hash);
                p.sendResourcePacks(ResourcePackRequest.resourcePackRequest().packs(info).replace(false)
                        .required(required).prompt(prompt.isBlank() ? null : Msg.parse(prompt)).build());
            } else if (!want && sent.contains(u)) {
                sent.remove(u);
                loaded.remove(u);
                p.removeResourcePacks(packId);
            }
        }
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent e) {
        if (packId == null || !packId.equals(e.getID())) return;
        Player p = e.getPlayer();
        switch (e.getStatus()) {
            case SUCCESSFULLY_LOADED -> loaded.add(p.getUniqueId());
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> {
                loaded.remove(p.getUniqueId());
                if (!sent.contains(p.getUniqueId())) return;
                if (required) {
                    Msg.send(p, "<red>Для этого режима нужен ресурспак с моделями оружия. Разреши серверные ресурспаки и зайди снова.");
                    Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) plugin.sessions().leave(p); });
                } else if (e.getStatus() == PlayerResourcePackStatusEvent.Status.DECLINED) {
                    Msg.send(p, "<gray>Без ресурспака оружие будет выглядеть как арбалет, а выстрелы — звучать по-ванильному.");
                } else if (e.getStatus() != PlayerResourcePackStatusEvent.Status.DISCARDED) {
                    Msg.send(p, "<gray>Ресурспак оружия не загрузился (" + e.getStatus().name().toLowerCase(Locale.ROOT) + ").");
                }
            }
            default -> {}
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        sent.remove(e.getPlayer().getUniqueId());
        loaded.remove(e.getPlayer().getUniqueId());
    }
}
