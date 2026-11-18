package ru.mgp.bot;

import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.mgp.MGPPlugin;
import ru.mgp.config.ModeConfig;
import ru.mgp.session.GameSession;
import ru.mgp.util.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /mgp bot — тестовые боты:
 *   add [кол-во] [поведение] — рядом с тобой; если ты в лобби ожидания, они заходят в твою игру;
 *   join <режим> [кол-во] — встать в очередь режима, как через /play (без кол-ва — все свободные боты);
 *   mode <idle|fight|follow> [ник|all]; goto [ник|all] — идти к тебе; tp [ник|all]; list; remove [ник|all].
 */
public final class BotCommands {
    private BotCommands() {}

    public static final List<String> SUBS = List.of("add", "join", "mode", "goto", "tp", "list", "remove");
    public static final List<String> MODES = List.of("idle", "fight", "follow");
    private static final int MAX_AT_ONCE = 32;

    public static void handle(MGPPlugin plugin, CommandSender sender, String[] a) {
        BotService svc = plugin.bots();
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "help";
        Player me = sender instanceof Player p ? p : null;
        if (!sub.equals("list") && !sub.equals("remove") && !sub.equals("help") && !svc.available()) {
            Msg.send(sender, "<red>Боты не работают на этой версии сервера: " + Msg.esc(String.valueOf(svc.error())));
            return;
        }
        switch (sub) {
            case "add" -> {
                int n = a.length > 2 ? count(a[2]) : 1;
                BotService.Mode mode = a.length > 3 ? mode(a[3]) : BotService.Mode.FIGHT;
                if (n <= 0 || mode == null) { help(sender); return; }
                GameSession s = me == null ? null : plugin.sessions().of(me);
                if (s != null && !s.canJoin(1)) {
                    Msg.send(sender, "<red>В эту игру уже нельзя зайти (" + s.state() + "). Добавь ботов в лобби ожидания.");
                    return;
                }
                int made = 0, joined = 0;
                for (int i = 0; i < n; i++) {
                    Player b = svc.spawn(near(base(me)), mode, me == null ? null : me.getUniqueId());
                    if (b == null) break;
                    made++;
                    if (s != null && s.canJoin(1) && s.addPlayer(b)) joined++;
                }
                if (made == 0) { Msg.send(sender, "<red>Бот не создан — смотри консоль. " + Msg.esc(String.valueOf(svc.error()))); return; }
                Msg.send(sender, "<green>Ботов: +" + made + (s != null ? " <gray>(в игре #" + s.id + ": " + joined + ")" : "")
                        + " <gray>— поведение " + mode.name().toLowerCase(Locale.ROOT)
                        + (s == null ? ". В очередь: <yellow>/mgp bot join <режим>" : ""));
            }
            case "join" -> {
                if (a.length < 3) { help(sender); return; }
                ModeConfig m = plugin.registry().mode(a[2]);
                if (m == null) { Msg.send(sender, "<red>Нет режима " + Msg.esc(a[2])); return; }
                List<Player> who = new ArrayList<>();
                if (a.length > 3) {
                    int n = count(a[3]);
                    if (n <= 0) { help(sender); return; }
                    for (int i = 0; i < n; i++) {
                        Player b = svc.spawn(near(base(me)), BotService.Mode.FIGHT, me == null ? null : me.getUniqueId());
                        if (b != null) who.add(b);
                    }
                } else {
                    for (Player b : svc.online()) if (plugin.sessions().of(b) == null) who.add(b);
                }
                if (who.isEmpty()) { Msg.send(sender, "<red>Нет свободных ботов. <gray>/mgp bot join " + m.id + " <кол-во>"); return; }
                for (Player b : who) plugin.sessions().join(b, m.id);
                long in = who.stream().filter(b -> plugin.sessions().of(b) != null).count();
                Msg.send(sender, "<green>В очередь " + m.id + ": " + in + " из " + who.size() + " ботов"
                        + (in < who.size() ? " <gray>(остальным не нашлось места — смотри max-players / готовность карт)" : ""));
            }
            case "mode" -> {
                BotService.Mode mode = a.length > 2 ? mode(a[2]) : null;
                if (mode == null) { help(sender); return; }
                List<Player> who = pick(svc, sender, a, 3);
                for (Player b : who) svc.setMode(b, mode, me != null ? me.getUniqueId() : null, null);
                Msg.send(sender, "<green>Поведение " + mode.name().toLowerCase(Locale.ROOT) + ": " + who.size() + " ботов");
            }
            case "goto" -> {
                if (me == null) { Msg.send(sender, "Только в игре."); return; }
                List<Player> who = pick(svc, sender, a, 2);
                for (Player b : who) svc.setMode(b, BotService.Mode.GOTO, me.getUniqueId(), me.getLocation());
                Msg.send(sender, "<green>Идут к тебе: " + who.size() + " ботов <gray>(дерутся по дороге; вернуть: /mgp bot mode fight)");
            }
            case "tp" -> {
                if (me == null) { Msg.send(sender, "Только в игре."); return; }
                List<Player> who = pick(svc, sender, a, 2);
                for (Player b : who) b.teleport(near(me.getLocation()));
                Msg.send(sender, "<green>Перенесено: " + who.size() + " ботов");
            }
            case "list" -> {
                Msg.send(sender, "<yellow>Ботов: " + svc.count());
                for (BotService.Bot b : svc.all()) {
                    Player p = org.bukkit.Bukkit.getPlayer(b.id);
                    GameSession s = p == null ? null : plugin.sessions().of(p);
                    String where = s == null ? "вне игры" : "#" + s.id + " " + s.mode.id + " " + s.state()
                            + (s.isPlaying() ? (s.isAlive(p) ? ", жив" : ", наблюдает") : "");
                    String team = s != null && p != null ? s.logic().zoneTeam(p) : null;
                    Msg.send(sender, "<gray>" + b.name + " — " + b.mode.name().toLowerCase(Locale.ROOT) + ", " + where
                            + (team != null ? ", команда " + team : "") + (p != null && !p.isDead() ? ", " + (int) Math.ceil(p.getHealth()) + "❤" : ""));
                }
            }
            case "remove" -> {
                if (a.length > 2 && !a[2].equalsIgnoreCase("all")) {
                    List<Player> who = pick(svc, sender, a, 2);
                    for (Player b : who) svc.remove(b);
                    Msg.send(sender, "<green>Убрано ботов: " + who.size());
                } else {
                    Msg.send(sender, "<green>Убрано ботов: " + svc.removeAll());
                }
            }
            default -> help(sender);
        }
    }

    /** Боты из аргумента i: ник бота или all/пусто — все. */
    private static List<Player> pick(BotService svc, CommandSender sender, String[] a, int i) {
        if (a.length <= i || a[i].equalsIgnoreCase("all")) return svc.online();
        List<Player> out = new ArrayList<>();
        for (Player b : svc.online()) if (b.getName().equalsIgnoreCase(a[i])) out.add(b);
        if (out.isEmpty()) Msg.send(sender, "<red>Нет бота " + Msg.esc(a[i]) + " <gray>(/mgp bot list)");
        return out;
    }

    private static int count(String s) {
        try { return Math.min(MAX_AT_ONCE, Integer.parseInt(s)); } catch (NumberFormatException e) { return -1; }
    }

    private static BotService.Mode mode(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "idle", "stand", "стоять" -> BotService.Mode.IDLE;
            case "fight", "бой" -> BotService.Mode.FIGHT;
            case "follow", "за", "следовать" -> BotService.Mode.FOLLOW;
            default -> null;
        };
    }

    /** Где ставить новых ботов: у админа, из консоли — у спавна хаба (первого мира). */
    private static Location base(Player me) {
        return me != null ? me.getLocation() : org.bukkit.Bukkit.getWorlds().get(0).getSpawnLocation().add(0.5, 0, 0.5);
    }

    private static Location near(Location l) {
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        Location out = l.clone().add(r.nextDouble(-1.5, 1.5), 0, r.nextDouble(-1.5, 1.5));
        return out.getBlock().isPassable() && out.clone().add(0, 1, 0).getBlock().isPassable() ? out : l.clone();
    }

    public static List<String> tab(MGPPlugin plugin, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 2) out.addAll(SUBS);
        else if (a.length == 3) {
            switch (a[1].toLowerCase(Locale.ROOT)) {
                case "add" -> out.addAll(List.of("1", "2", "4", "8"));
                case "join" -> plugin.registry().modes().forEach(m -> out.add(m.id));
                case "mode" -> out.addAll(MODES);
                case "goto", "tp", "remove" -> { out.add("all"); plugin.bots().online().forEach(b -> out.add(b.getName())); }
            }
        } else if (a.length == 4) {
            switch (a[1].toLowerCase(Locale.ROOT)) {
                case "add" -> out.addAll(MODES);
                case "join" -> out.addAll(List.of("1", "2", "4", "8"));
                case "mode" -> { out.add("all"); plugin.bots().online().forEach(b -> out.add(b.getName())); }
            }
        }
        return out;
    }

    public static void help(CommandSender s) {
        Msg.send(s, "<gold>Тестовые боты <gray>(настоящие игроки без клиента):");
        Msg.send(s, "<yellow>/mgp bot add [кол-во] [idle|fight|follow] <gray>— рядом с тобой; в лобби ожидания — сразу в твою игру");
        Msg.send(s, "<yellow>/mgp bot join <режим> [кол-во] <gray>— в очередь режима (без кол-ва — все свободные боты)");
        Msg.send(s, "<yellow>/mgp bot mode <idle|fight|follow> [ник|all] <gray>— стоять / драться / держаться возле тебя");
        Msg.send(s, "<yellow>/mgp bot goto [ник|all] <gray>— идти к тебе (проверить зоны и триггеры)");
        Msg.send(s, "<yellow>/mgp bot tp [ник|all] <gray>| <yellow>/mgp bot list <gray>| <yellow>/mgp bot remove [ник|all]");
    }
}
