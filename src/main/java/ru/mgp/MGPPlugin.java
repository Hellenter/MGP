package ru.mgp;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import ru.mgp.command.MgpCommand;
import ru.mgp.config.ConfigRegistry;
import ru.mgp.listener.GameListener;
import ru.mgp.listener.MenuListener;
import ru.mgp.listener.PlayerListener;
import ru.mgp.panel.PanelServer;
import ru.mgp.session.HubService;
import ru.mgp.session.SessionManager;
import ru.mgp.session.VisibilityService;
import ru.mgp.ui.SidebarService;
import ru.mgp.util.Keys;
import ru.mgp.util.Msg;
import ru.mgp.world.MapImporter;
import ru.mgp.world.WorldService;

import java.io.File;

public final class MGPPlugin extends JavaPlugin {
    private WorldService worlds;
    private ConfigRegistry registry;
    private SessionManager sessions;
    private HubService hub;
    private VisibilityService visibility;
    private SidebarService sidebars;
    private PanelServer panel;
    private MapImporter importer;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (String res : new String[]{"modes/hunger_games.yml", "modes/build_battle.yml",
                "maps/hg_example.yml", "maps/bb_example.yml"}) {
            if (!new File(getDataFolder(), res).exists()) saveResource(res, false);
        }
        Keys.init(this);
        Msg.setPrefix(getConfig().getString("prefix", ""));

        worlds = new WorldService(this);
        worlds.cleanupLeftovers();
        importer = new MapImporter(this);
        registry = new ConfigRegistry(this);
        registry.load();
        hub = new HubService(this);
        visibility = new VisibilityService(this);
        sidebars = new SidebarService(this);
        sessions = new SessionManager(this);

        var pm = Bukkit.getPluginManager();
        pm.registerEvents(new MenuListener(this), this);
        pm.registerEvents(new PlayerListener(this), this);
        pm.registerEvents(new GameListener(this), this);

        MgpCommand cmd = new MgpCommand(this);
        for (String name : new String[]{"mgp", "leave", "play"}) {
            PluginCommand c = getCommand(name);
            if (c != null) { c.setExecutor(cmd); c.setTabCompleter(cmd); }
        }

        sessions.start();
        panel = new PanelServer(this);
        panel.start();
        // на случай /reload: вернуть всех онлайн-игроков в хаб
        for (Player p : Bukkit.getOnlinePlayers()) hub.send(p);
    }

    @Override
    public void onDisable() {
        if (panel != null) panel.stop();
        if (sessions != null) sessions.shutdown();
    }

    public void reloadAll() {
        reloadConfig();
        Msg.setPrefix(getConfig().getString("prefix", ""));
        registry.load();
    }

    public WorldService worlds() { return worlds; }
    public ConfigRegistry registry() { return registry; }
    public SessionManager sessions() { return sessions; }
    public HubService hub() { return hub; }
    public VisibilityService visibility() { return visibility; }
    public SidebarService sidebars() { return sidebars; }
    public PanelServer panel() { return panel; }
    public MapImporter importer() { return importer; }
}
