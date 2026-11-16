package ru.mgp.friend;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import ru.mgp.MGPPlugin;
import ru.mgp.util.Msg;

import java.util.*;

/** /friend (/f) — как на Hypixel: /f <ник> или /f add <ник>, accept, deny, remove, list, menu. */
public final class FriendCommand implements TabExecutor {
    private static final List<String> SUBS = List.of("add", "accept", "deny", "remove", "list", "menu", "help");
    private final MGPPlugin plugin;

    public FriendCommand(MGPPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Только в игре."); return true; }
        FriendService fs = plugin.friends();
        if (a.length == 0) { new FriendsMenu(plugin, p).open(p); return true; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "add", "request" -> { if (a.length < 2) help(p); else add(p, a[1]); }
            case "accept" -> fs.accept(p, a.length > 1 ? a[1] : null);
            case "deny", "decline" -> fs.deny(p, a.length > 1 ? a[1] : null);
            case "remove", "delete", "del" -> { if (a.length < 2) help(p); else fs.remove(p, fs.resolve(p, a[1])); }
            case "list", "l" -> fs.list(p);
            case "menu" -> new FriendsMenu(plugin, p).open(p);
            case "help" -> help(p);
            default -> add(p, a[0]); // /f <ник>
        }
        return true;
    }

    private void add(Player p, String name) {
        Player t = Bukkit.getPlayerExact(name);
        if (t == null) { FriendService.msg(p, "<red>Игрок " + Msg.esc(name) + " не в сети."); return; }
        plugin.friends().add(p, t);
    }

    private void help(Player p) {
        for (String l : List.of(
                "<green>Команды друзей:",
                "<yellow>/f <ник></yellow> <gray>или <yellow>/f add <ник></yellow> <gray>— запрос в друзья",
                "<yellow>/f accept <ник></yellow> <gray>/ <yellow>deny</yellow> — принять или отклонить",
                "<yellow>/f remove <ник></yellow> <gray>— удалить из друзей",
                "<yellow>/f list</yellow> <gray>— кто в сети, <yellow>/f</yellow> — меню",
                "<gray>Друзья стоят первыми в меню пати: клик — приглашение.")) p.sendMessage(Msg.parse(l));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) return List.of();
        FriendService fs = plugin.friends();
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.addAll(SUBS);
            for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(p) && !fs.areFriends(p.getUniqueId(), o.getUniqueId())) out.add(o.getName());
        } else if (a.length == 2) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "remove", "delete", "del" -> { for (UUID u : fs.friends(p.getUniqueId())) out.add(FriendService.name(u)); }
                case "accept", "deny", "decline" -> { for (UUID u : fs.incoming(p.getUniqueId())) out.add(FriendService.name(u)); }
                case "add", "request" -> { for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(p) && !fs.areFriends(p.getUniqueId(), o.getUniqueId())) out.add(o.getName()); }
                default -> {}
            }
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
