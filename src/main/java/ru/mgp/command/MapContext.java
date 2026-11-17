package ru.mgp.command;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import ru.mgp.MGPPlugin;
import ru.mgp.config.MapConfig;
import ru.mgp.session.GameSession;
import ru.mgp.util.Pos;
import ru.mgp.util.Region;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * «Текущая карта» строителя: с ней команды настройки работают без id карты (/mgp setup spawn вместо /mgp setup spawn jungle).
 * Как определяется:
 *  1) игрок в мире идущей игры — карта этой игры;
 *  2) игрок стоит внутри области, из которой карту импортировали (source.world + source.min/max, ±8 блоков по горизонтали);
 *     если таких карт несколько — выбранная вручную (/mgp map), иначе самая маленькая;
 *  3) выбранная вручную /mgp map <карта>;
 *  4) если карта на сервере всего одна — она.
 */
public final class MapContext {
    public enum Source { GAME, INSIDE, CHOSEN, ONLY }

    public record Current(MapConfig map, Source source) {
        public String how() {
            return switch (source) {
                case GAME -> "карта этой игры";
                case INSIDE -> "ты стоишь в ней";
                case CHOSEN -> "выбрана через /mgp map";
                case ONLY -> "единственная карта";
            };
        }
    }

    private static final int MARGIN = 8;

    private final MGPPlugin plugin;
    private final Map<UUID, String> chosen = new HashMap<>();

    public MapContext(MGPPlugin plugin) { this.plugin = plugin; }

    public void choose(Player p, String mapId) {
        if (mapId == null) chosen.remove(p.getUniqueId());
        else chosen.put(p.getUniqueId(), mapId);
    }

    public String chosen(Player p) { return chosen.get(p.getUniqueId()); }

    public MapConfig map(Player p) {
        Current c = current(p);
        return c == null ? null : c.map();
    }

    public Current current(Player p) {
        GameSession s = plugin.sessions().byWorld(p.getWorld());
        if (s != null && s.map() != null) {
            MapConfig m = plugin.registry().map(s.map().id);
            if (m != null) return new Current(m, Source.GAME);
        }
        MapConfig pick = plugin.registry().map(chosen.getOrDefault(p.getUniqueId(), ""));
        MapConfig best = null;
        long bestVol = Long.MAX_VALUE;
        Location l = p.getLocation();
        for (MapConfig m : plugin.registry().maps()) {
            Region r = sourceRegion(m);
            if (r == null || !p.getWorld().getName().equals(m.yaml.getString("source.world"))) continue;
            if (l.getBlockX() < r.minX() - MARGIN || l.getBlockX() > r.maxX() + MARGIN
                    || l.getBlockZ() < r.minZ() - MARGIN || l.getBlockZ() > r.maxZ() + MARGIN) continue;
            if (m == pick) return new Current(m, Source.INSIDE);
            long vol = (long) (r.maxX() - r.minX() + 1) * (r.maxZ() - r.minZ() + 1);
            if (vol < bestVol) { bestVol = vol; best = m; }
        }
        if (best != null) return new Current(best, Source.INSIDE);
        if (pick != null) return new Current(pick, Source.CHOSEN);
        if (plugin.registry().maps().size() == 1) return new Current(plugin.registry().maps().iterator().next(), Source.ONLY);
        return null;
    }

    /** Область, из которой карту импортировали, или null (карта создана через setup create). */
    public static Region sourceRegion(MapConfig m) {
        Pos a = Pos.parse(m.yaml.getString("source.min")), b = Pos.parse(m.yaml.getString("source.max"));
        return a == null || b == null ? null : Region.of(a, b);
    }

    /** Мир, в котором заданы координаты карты: исходный мир импорта, если загружен, иначе мир игрока. */
    public static World world(Player p, MapConfig m) {
        String wn = m.yaml.getString("source.world");
        World w = wn == null ? null : Bukkit.getWorld(wn);
        return w != null ? w : p.getWorld();
    }

    /** Куда телепортировать строителя «к карте»: спавн наблюдателей, первый спавн, центр или середина области. */
    public static Location anchor(Player p, MapConfig m) {
        World w = world(p, m);
        if (m.spectator != null) return m.spectator.toLocation(w);
        if (!m.spawns.isEmpty()) return m.spawns.get(0).toLocation(w);
        if (!m.spawnPoints.isEmpty()) return m.spawnPoints.get(0).pos().toLocation(w);
        if (m.center != null) return m.center.toLocation(w).add(0.5, 1, 0.5);
        Region r = sourceRegion(m);
        if (r == null) return null;
        int x = (r.minX() + r.maxX()) / 2, z = (r.minZ() + r.maxZ()) / 2;
        int y = Math.min(r.maxY() + 1, w.getHighestBlockYAt(x, z) + 1);
        return new Location(w, x + 0.5, y, z + 0.5, p.getLocation().getYaw(), 30);
    }
}
