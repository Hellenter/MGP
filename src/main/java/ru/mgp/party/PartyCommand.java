package ru.mgp.party;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import ru.mgp.MGPPlugin;
import ru.mgp.util.Msg;

import java.util.*;

/** /party (/p) и /pc — как на Hypixel: /p <ник> приглашает, /p accept, leave, kick, transfer, disband, list, warp, chat. */
public final class PartyCommand implements TabExecutor {
    private static final List<String> SUBS = List.of("invite", "accept", "deny", "leave", "list", "chat", "warp", "kick", "transfer", "disband", "menu", "help");
    private final MGPPlugin plugin;

    public PartyCommand(MGPPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Только в игре."); return true; }
        PartyService ps = plugin.parties();
        if (cmd.getName().equalsIgnoreCase("pc")) {
            if (a.length == 0) PartyService.msg(p, "<red>/pc <сообщение>");
            else ps.chat(p, String.join(" ", a));
            return true;
        }
        if (a.length == 0) { new PartyMenu(plugin, p).open(p); return true; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "invite", "inv" -> { if (a.length < 2) help(p); else invite(p, a[1]); }
            case "accept", "join" -> ps.accept(p, a.length > 1 ? a[1] : null);
            case "deny", "decline" -> ps.deny(p, a.length > 1 ? a[1] : null);
            case "leave" -> ps.leave(p);
            case "list", "info" -> ps.list(p);
            case "chat" -> { if (a.length < 2) PartyService.msg(p, "<red>/p chat <сообщение>"); else ps.chat(p, String.join(" ", Arrays.copyOfRange(a, 1, a.length))); }
            case "warp" -> ps.warp(p);
            case "kick", "remove" -> { if (a.length < 2) help(p); else ps.kick(p, a[1]); }
            case "transfer", "promote" -> { if (a.length < 2) help(p); else ps.transfer(p, a[1]); }
            case "disband" -> ps.disband(p);
            case "menu" -> new PartyMenu(plugin, p).open(p);
            case "help" -> help(p);
            default -> invite(p, a[0]); // /p <ник> — как на Hypixel
        }
        return true;
    }

    private void invite(Player p, String name) {
        Player t = Bukkit.getPlayerExact(name);
        if (t == null) { PartyService.msg(p, "<red>Игрок " + Msg.esc(name) + " не в сети."); return; }
        plugin.parties().invite(p, t);
    }

    private void help(Player p) {
        for (String l : List.of(
                "<blue>Команды пати:",
                "<yellow>/p <ник></yellow> <gray>— пригласить (приглашение действует 60 сек.)",
                "<yellow>/p accept <ник></yellow> <gray>/ <yellow>deny</yellow> — принять или отклонить",
                "<yellow>/p list</yellow> <gray>— состав пати, <yellow>/p leave</yellow> — выйти",
                "<yellow>/pc <текст></yellow> <gray>— чат пати",
                "<gray>Лидер: <yellow>/p warp</yellow> собрать всех, <yellow>/p kick</yellow>, <yellow>/p transfer</yellow>, <yellow>/p disband</yellow>",
                "<gray>Игры запускает лидер — вся пати попадает в одну арену.",
                "<gray>Без аргументов (или предмет «Пати» в хабе) — меню.")) p.sendMessage(Msg.parse(l));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (cmd.getName().equalsIgnoreCase("pc") || !(s instanceof Player p)) return List.of();
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.addAll(SUBS);
            for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(p)) out.add(o.getName());
        } else if (a.length == 2) {
            String sub = a[0].toLowerCase(Locale.ROOT);
            Party party = plugin.parties().of(p);
            if (List.of("kick", "remove", "transfer", "promote").contains(sub) && party != null) {
                for (UUID u : party.members) if (!u.equals(p.getUniqueId())) out.add(PartyService.name(u));
            } else if (List.of("invite", "inv", "accept", "join", "deny", "decline").contains(sub)) {
                for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(p)) out.add(o.getName());
            }
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
