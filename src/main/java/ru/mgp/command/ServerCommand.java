package ru.mgp.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import ru.mgp.MGPPlugin;
import ru.mgp.network.LinkedServers;
import ru.mgp.util.Msg;

import java.util.List;
import java.util.Locale;

/** /server [id] — список серверов сети или переход на сервер; /smp — сразу на SMP. */
public final class ServerCommand implements TabExecutor {
    private final MGPPlugin plugin;

    public ServerCommand(MGPPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (!(sender instanceof Player p)) {
            Msg.send(sender, "<red>Только для игроков.");
            return true;
        }
        String target = cmd.getName().equalsIgnoreCase("smp") ? "smp" : (a.length > 0 ? a[0] : null);
        if (target == null) {
            if (plugin.servers().all().isEmpty()) {
                Msg.send(p, "<gray>Других серверов в сети нет.");
                return true;
            }
            Msg.send(p, "<gray>Серверы: ");
            for (LinkedServers.Server s : plugin.servers().all()) {
                Msg.send(p, "<gray> • <yellow>/server " + s.id() + "</yellow> — " + s.name());
            }
            return true;
        }
        plugin.servers().transfer(p, target);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] a) {
        if (!cmd.getName().equalsIgnoreCase("server") || a.length != 1) return List.of();
        String prefix = a[0].toLowerCase(Locale.ROOT);
        return plugin.servers().all().stream().map(LinkedServers.Server::id).filter(id -> id.startsWith(prefix)).toList();
    }
}
