package ru.mgp.command;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import ru.mgp.util.Pos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Рамка выделения /mgp setup pos1 / pos2 — как у структурного блока или CUI для WorldEdit:
 * рёбра коробки (видны сквозь блоки) и подсвеченные углы (pos1 красный, pos2 синий).
 * Рисует {@link Outline}: на ванильном клиенте, сплошными линиями на любом расстоянии. Видит только сам игрок.
 */
final class SelectionDisplay {
    private static final Color EDGE_GLOW = Color.fromRGB(0xFEFEFE);
    private static final Color P1_GLOW = Color.fromRGB(255, 60, 60);
    private static final Color P2_GLOW = Color.fromRGB(60, 140, 255);

    private final Plugin plugin;
    private final Map<UUID, Pos> pos1, pos2;
    private final Map<UUID, String> world;
    private final Map<UUID, Outline> frames = new HashMap<>();

    SelectionDisplay(Plugin plugin, Map<UUID, Pos> pos1, Map<UUID, Pos> pos2, Map<UUID, String> world) {
        this.plugin = plugin;
        this.pos1 = pos1;
        this.pos2 = pos2;
        this.world = world;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 5L, 5L);
    }

    /** Перестроить рамку игрока после изменения выделения. */
    void update(Player p) {
        UUID id = p.getUniqueId();
        remove(id);
        Pos a = pos1.get(id), b = pos2.get(id);
        String wn = world.get(id);
        World w = wn == null ? null : Bukkit.getWorld(wn);
        if ((a == null && b == null) || w == null) return;
        Pos c1 = a, c2 = b;
        if (a == null) a = b;
        if (b == null) b = a;
        Outline o = new Outline(plugin, p, w);
        o.box(Math.min(a.bx(), b.bx()), Math.min(a.by(), b.by()), Math.min(a.bz(), b.bz()),
                Math.max(a.bx(), b.bx()) + 1, Math.max(a.by(), b.by()) + 1, Math.max(a.bz(), b.bz()) + 1, Material.WHITE_CONCRETE, EDGE_GLOW);
        if (c1 != null) corner(o, c1, Material.RED_STAINED_GLASS, P1_GLOW);
        if (c2 != null && !(c1 != null && c1.bx() == c2.bx() && c1.by() == c2.by() && c1.bz() == c2.bz()))
            corner(o, c2, Material.BLUE_STAINED_GLASS, P2_GLOW);
        frames.put(id, o);
        o.refresh();
    }

    void remove(UUID id) {
        Outline o = frames.remove(id);
        if (o != null) o.hide();
    }

    void removeAll() {
        for (UUID id : new ArrayList<>(frames.keySet())) remove(id);
    }

    /** Убирает рамки вышедших игроков, переносит рамку за игроком и поправляет толщину рёбер. */
    private void tick() {
        for (UUID id : new ArrayList<>(frames.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) { remove(id); continue; }
            frames.get(id).refresh();
        }
    }

    private static void corner(Outline o, Pos c, Material m, Color glow) {
        double g = 0.02;
        o.cube(c.bx() - g, c.by() - g, c.bz() - g, c.bx() + 1 + g, c.by() + 1 + g, c.bz() + 1 + g, m, glow);
    }
}
