package ru.mgp.ui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.mgp.MGPPlugin;
import ru.mgp.session.GameSession;
import ru.mgp.util.Msg;

import java.util.*;

/** Обновляет инфо-панель всем игрокам раз в секунду. Строки берутся из конфигов. */
public final class SidebarService {
    private final MGPPlugin plugin;
    private final Map<UUID, Sidebar> boards = new HashMap<>();

    public SidebarService(MGPPlugin plugin) { this.plugin = plugin; }

    public void updateAll() {
        for (Player p : Bukkit.getOnlinePlayers()) update(p);
    }

    public void update(Player p) {
        Sidebar sb = boards.computeIfAbsent(p.getUniqueId(), k -> new Sidebar());
        if (p.getScoreboard() != sb.board()) p.setScoreboard(sb.board());
        GameSession s = plugin.sessions().of(p);
        String title;
        List<String> lines;
        Map<String, String> ph;
        if (s != null) {
            title = s.mode.sidebarTitle;
            lines = s.sidebarLines();
            ph = s.placeholders(p);
        } else {
            title = plugin.getConfig().getString("hub.sidebar.title", "<gold><bold>МИНИИГРЫ");
            lines = plugin.getConfig().getStringList("hub.sidebar.lines");
            ph = new HashMap<>();
            ph.put("online", String.valueOf(Bukkit.getOnlinePlayers().size()));
            ph.put("player", Msg.esc(p.getName()));
            for (var m : plugin.registry().modes()) ph.put("players_" + m.id, String.valueOf(plugin.sessions().playersIn(m.id)));
        }
        List<net.kyori.adventure.text.Component> out = new ArrayList<>();
        for (String line : lines) out.add(Msg.parse(line, ph));
        sb.update(Msg.parse(title, ph), out);
        // прятки и т.п.: ники не видно ни через стены, ни вблизи — никакой лишней подсказки
        Set<String> hidden = new HashSet<>();
        if (s != null && s.isPlaying()) {
            boolean all = s.mode.settings.getBoolean("hide-nametags", false);
            // осада и т.п.: ники противников скрыты, союзников видно
            for (Player o : s.online()) if (all || !s.logic().nametagVisible(p, o)) hidden.add(o.getName());
        }
        sb.teams(s != null && (s.isPlaying() || s.state() == ru.mgp.session.GameSession.State.ENDING) ? s.logic().teamColors() : java.util.Map.of(), hidden);
    }

    public void remove(Player p) { boards.remove(p.getUniqueId()); }
}
