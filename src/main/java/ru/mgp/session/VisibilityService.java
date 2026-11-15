package ru.mgp.session;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.mgp.MGPPlugin;

/**
 * Изоляция сессий: игроки видят (в мире и в табе) только тех, кто в той же сессии.
 * Живые игроки не видят наблюдателей, наблюдатели видят всех.
 */
public final class VisibilityService {
    private final MGPPlugin plugin;

    public VisibilityService(MGPPlugin plugin) { this.plugin = plugin; }

    public void refresh(Player p) {
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o == p) continue;
            apply(p, o);
            apply(o, p);
        }
    }

    public void refreshAll(GameSession s) {
        for (Player p : s.online()) refresh(p);
    }

    private void apply(Player viewer, Player target) {
        if (canSee(viewer, target)) viewer.showPlayer(plugin, target);
        else viewer.hidePlayer(plugin, target);
    }

    public boolean canSee(Player viewer, Player target) {
        GameSession a = plugin.sessions().of(viewer);
        GameSession b = plugin.sessions().of(target);
        if (a != b) return false;
        if (a == null) return true;
        return !a.isSpectator(target.getUniqueId()) || a.isSpectator(viewer.getUniqueId());
    }

    /** Для чата: одна и та же «комната». */
    public boolean sameRoom(Player a, Player b) {
        return plugin.sessions().of(a) == plugin.sessions().of(b);
    }
}
