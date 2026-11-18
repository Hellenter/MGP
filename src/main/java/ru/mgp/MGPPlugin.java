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
        for (String res : new String[]{"modes/hunger_games.yml", "modes/build_battle.yml", "modes/hide_and_seek.yml", "modes/prop_hunt.yml", "modes/siege.yml", "modes/assault.yml",
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
        friends = new ru.mgp.friend.FriendService(this);
        friends.load();
        guns = new ru.mgp.gun.GunService(this); // до магазинов: товары "GUN ..." собираются через него
        guns.load();
        shops = new ru.mgp.shop.ShopService(this);
        shops.load();

        var pm = Bukkit.getPluginManager();
        pm.registerEvents(new MenuListener(this), this);
        pm.registerEvents(new PlayerListener(this), this);
        pm.registerEvents(new GameListener(this), this);
        pm.registerEvents(new ru.mgp.session.ExplosiveArrows(this), this);
        spawnProtection = new ru.mgp.session.SpawnProtection(this);
        pm.registerEvents(spawnProtection, this);
        pm.registerEvents(guns, this);
        wand = new ru.mgp.editor.ScriptWand(this);
        pm.registerEvents(wand, this);
        pm.registerEvents(shops, this);
        pm.registerEvents(friends, this);
        pm.registerEvents(new ru.mgp.listener.FreeLapisListener(this), this);
        npcs = new ru.mgp.npc.NpcService(this);
        npcs.load();
        pm.registerEvents(npcs, this);
        npcs.start();
        bots = new ru.mgp.bot.BotService(this);
        pm.registerEvents(bots, this);
        stats = new ru.mgp.stats.StatsService(this);
        stats.load();
        pm.registerEvents(stats, this);
        leaderboards = new ru.mgp.stats.Leaderboards(this, stats);
        leaderboards.load();

        mapContext = new ru.mgp.command.MapContext(this);
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
        ru.mgp.friend.FriendCommand friendCmd = new ru.mgp.friend.FriendCommand(this);
        PluginCommand fc = getCommand("friend");
        if (fc != null) { fc.setExecutor(friendCmd); fc.setTabCompleter(friendCmd); }
        ru.mgp.stats.StatsCommand statsCmd = new ru.mgp.stats.StatsCommand(this);
        PluginCommand sc = getCommand("stats");
        if (sc != null) { sc.setExecutor(statsCmd); sc.setTabCompleter(statsCmd); }
        ServerCommand serverCmd = new ServerCommand(this);
        for (String name : new String[]{"server", "smp"}) {
            PluginCommand c = getCommand(name);
            if (c != null) { c.setExecutor(serverCmd); c.setTabCompleter(serverCmd); }
        }

        sessions.start();
        stats.start();
        leaderboards.start();
        panel = new PanelServer(this);
        panel.start();
        // на случай /reload: вернуть всех онлайн-игроков в хаб
        for (Player p : Bukkit.getOnlinePlayers()) hub.send(p);
    }

    private MgpCommand mgpCommand;

    @Override
    public void onDisable() {
        if (bots != null) bots.shutdown(); // до сессий: боты выходят как обычные игроки
        if (mgpCommand != null) mgpCommand.shutdown();
        if (guns != null) guns.shutdown();
        if (wand != null) wand.shutdown();
        if (npcs != null) npcs.shutdown();
        if (panel != null) panel.stop();
        if (sessions != null) sessions.shutdown();
        if (leaderboards != null) leaderboards.shutdown();
        if (stats != null) stats.shutdown(); // после сессий: их итоги уже записаны
    }

    /**
     * Режим отладки в одиночку (config.yml → debug.solo, /mgp debug solo on|off): игра стартует с 1 игроком
     * и не заканчивается из-за того, что игроков или одной из команд не осталось. Время, цели и стадии работают как обычно.
     */
    public boolean soloDebug() { return getConfig().getBoolean("debug.solo", false); }

    public void setSoloDebug(boolean on) {
        getConfig().set("debug.solo", on);
        saveConfig();
    }

    public void reloadAll() {
        reloadConfig();
        Msg.setPrefix(getConfig().getString("prefix", ""));
        registry.load();
        servers.load();
        if (guns != null) guns.load();
        shops.load();
        if (npcs != null) npcs.load();
        if (leaderboards != null) leaderboards.load();
    }

    public WorldService worlds() { return worlds; }
    public ConfigRegistry registry() { return registry; }
    public SessionManager sessions() { return sessions; }

    private ru.mgp.party.PartyService parties;
    public ru.mgp.party.PartyService parties() { return parties; }
    private ru.mgp.friend.FriendService friends;
    public ru.mgp.friend.FriendService friends() { return friends; }
    public HubService hub() { return hub; }
    public VisibilityService visibility() { return visibility; }
    public SidebarService sidebars() { return sidebars; }
    public PanelServer panel() { return panel; }
    public MapImporter importer() { return importer; }
    public LinkedServers servers() { return servers; }

    private ru.mgp.shop.ShopService shops;
    public ru.mgp.shop.ShopService shops() { return shops; }

    private ru.mgp.gun.GunService guns;
    public ru.mgp.gun.GunService guns() { return guns; }

    private ru.mgp.editor.ScriptWand wand;
    public ru.mgp.editor.ScriptWand wand() { return wand; }

    private ru.mgp.command.MapContext mapContext;
    /** Текущая карта строителя (по месту или /mgp map). */
    public ru.mgp.command.MapContext mapContext() { return mapContext; }

    private ru.mgp.npc.NpcService npcs;
    public ru.mgp.npc.NpcService npcs() { return npcs; }

    private ru.mgp.bot.BotService bots;
    public ru.mgp.bot.BotService bots() { return bots; }

    private ru.mgp.session.SpawnProtection spawnProtection;
    public ru.mgp.session.SpawnProtection spawnProtection() { return spawnProtection; }

    private ru.mgp.stats.StatsService stats;
    public ru.mgp.stats.StatsService stats() { return stats; }

    private ru.mgp.stats.Leaderboards leaderboards;
    public ru.mgp.stats.Leaderboards leaderboards() { return leaderboards; }
}
