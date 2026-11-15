package ru.mgp;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import ru.mgp.command.MgpCommand;
import ru.mgp.command.ServerCommand;
import ru.mgp.network.LinkedServers;
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
    private LinkedServers servers;

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
        servers = new LinkedServers(this);
        servers.load();
        hub = new HubService(this);
        visibility = new VisibilityService(this);
        sidebars = new SidebarService(this);
        sessions = new SessionManager(this);
        parties = new ru.mgp.party.PartyService(this);

        var pm = Bukkit.getPluginManager();
        pm.registerEvents(new MenuListener(this), this);
        pm.registerEvents(new PlayerListener(this), this);
        pm.registerEvents(new GameListener(this), this);

        MgpCommand cmd = new MgpCommand(this);
        this.mgpCommand = cmd;
        for (String name : new String[]{"mgp", "leave", "play"}) {
            PluginCommand c = getCommand(name);
            if (c != null) { c.setExecutor(cmd); c.setTabCompleter(cmd); }
        }
        ru.mgp.party.PartyCommand partyCmd = new ru.mgp.party.PartyCommand(this);
        for (String name : new String[]{"party", "pc"}) {
            PluginCommand c = getCommand(name);
            if (c != null) { c.setExecutor(partyCmd); c.setTabCompleter(partyCmd); }
        }
        ServerCommand serverCmd = new ServerCommand(this);
        for (String name : new String[]{"server", "smp"}) {
            PluginCommand c = getCommand(name);
            if (c != null) { c.setExecutor(serverCmd); c.setTabCompleter(serverCmd); }
        }

        sessions.start();
        panel = new PanelServer(this);
        panel.start();
        // на случай /reload: вернуть всех онлайн-игроков в хаб
        for (Player p : Bukkit.getOnlinePlayers()) hub.send(p);
    }

    private MgpCommand mgpCommand;

    @Override
    public void onDisable() {
        if (mgpCommand != null) mgpCommand.shutdown();
        if (panel != null) panel.stop();
        if (sessions != null) sessions.shutdown();
    }

    public void reloadAll() {
        reloadConfig();
        Msg.setPrefix(getConfig().getString("prefix", ""));
        registry.load();
        servers.load();
    }

    public WorldService worlds() { return worlds; }
    public ConfigRegistry registry() { return registry; }
    public SessionManager sessions() { return sessions; }

    private ru.mgp.party.PartyService parties;
    public ru.mgp.party.PartyService parties() { return parties; }
    public HubService hub() { return hub; }
    public VisibilityService visibility() { return visibility; }
    public SidebarService sidebars() { return sidebars; }
    public PanelServer panel() { return panel; }
    public MapImporter importer() { return importer; }
    public LinkedServers servers() { return servers; }
}
