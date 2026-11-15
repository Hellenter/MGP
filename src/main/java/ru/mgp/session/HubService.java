package ru.mgp.session;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import ru.mgp.MGPPlugin;
import ru.mgp.ui.Hotbar;
import ru.mgp.util.Cfg;
import ru.mgp.util.PlayerUtil;
import ru.mgp.util.Pos;

/** Главное лобби (хаб): точка появления, предметы, сброс игрока. */
public final class HubService {
    private final MGPPlugin plugin;

    public HubService(MGPPlugin plugin) { this.plugin = plugin; }

    public Location location() {
        World w = Bukkit.getWorld(plugin.getConfig().getString("hub.world", "world"));
        if (w == null) w = Bukkit.getWorlds().get(0);
        Pos pos = Pos.parse(plugin.getConfig().getString("hub.spawn"));
        return pos != null ? pos.toLocation(w) : w.getSpawnLocation();
    }

    public World world() { return location().getWorld(); }

    public void send(Player p) {
        GameMode gm = Cfg.enumValue(GameMode.class, plugin.getConfig().getString("hub.gamemode"), GameMode.ADVENTURE);
        PlayerUtil.reset(p, gm);
        p.teleport(location());
        Hotbar.giveHub(p);
        plugin.visibility().refresh(p);
    }
}
