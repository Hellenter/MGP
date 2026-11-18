package ru.mgp.stats;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import ru.mgp.MGPPlugin;
import ru.mgp.util.Msg;

import java.util.*;

/**
 * /stats [ник] — меню статистики; /stats top <показатель> [режим] — топ в чат;
 * /stats reset <ник> [режим] — обнулить (mgp.admin).
 * Голограммы-топы в хабе: /mgp lb add|remove|move|list (см. {@link #leaderboard}).
 */
public final class StatsCommand implements TabExecutor {
    private final MGPPlugin plugin;

    public StatsCommand(MGPPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        StatsService stats = plugin.stats();
        if (a.length >= 1 && a[0].equalsIgnoreCase("top")) {
            Stat st = a.length >= 2 ? Stat.parse(a[1]) : Stat.WINS;
            if (st == null) { Msg.send(s, "<red>Показатели: " + ids()); return true; }
            String mode = a.length >= 3 ? a[2] : StatsService.ALL;
            if (!mode.equals(StatsService.ALL) && !stats.modeIds().contains(mode)) { Msg.send(s, "<red>Нет режима " + Msg.esc(mode)); return true; }
            List<StatsService.Row> rows = stats.top(st, mode, 10);
            Msg.send(s, "<gold><bold>Топ по " + st.byTitle + "</bold> <gray>(" + stats.modeName(mode) + "<gray>)");
            if (rows.isEmpty()) Msg.send(s, "<gray>Пока пусто.");
            for (int i = 0; i < rows.size(); i++)
                Msg.send(s, "<yellow>" + (i + 1) + ". <white>" + Msg.esc(rows.get(i).name()) + " <dark_gray>- <yellow>" + st.format(rows.get(i).value()));
            return true;
        }
        if (a.length >= 1 && a[0].equalsIgnoreCase("reset")) {
            if (!s.hasPermission("mgp.admin")) { Msg.send(s, "<red>Нет прав."); return true; }
            if (a.length < 2) { Msg.send(s, "<red>/stats reset <ник> [режим]"); return true; }
            StatsService.PlayerStats ps = stats.find(a[1]);
            if (ps == null) { Msg.send(s, "<red>Нет статистики игрока " + Msg.esc(a[1])); return true; }
            String mode = a.length >= 3 ? a[2] : null;
            if (!stats.reset(ps.uuid, mode)) { Msg.send(s, "<red>Нечего обнулять."); return true; }
            Msg.send(s, "<green>Статистика " + Msg.esc(ps.name) + (mode == null ? "" : " в режиме " + stats.modeName(mode)) + " <green>обнулена.");
            plugin.leaderboards().refresh();
            return true;
        }
        String target = a.length >= 1 ? a[0] : (s instanceof Player p ? p.getName() : null);
        if (target == null) { Msg.send(s, "/stats <ник> | top <показатель> [режим] | reset <ник> [режим]"); return true; }
        StatsService.PlayerStats ps = s instanceof Player p && a.length == 0 ? stats.get(p.getUniqueId()) : stats.find(target);
        if (ps == null && a.length >= 1) { Msg.send(s, "<red>У " + Msg.esc(target) + " ещё нет статистики."); return true; }
        String name = ps != null ? ps.name : target;
        if (s instanceof Player p) {
            new StatsMenu(plugin, ps, name).open(p);
        } else {
            Counts c = ps.counts(StatsService.ALL);
            Msg.send(s, name + ": игр " + c.played + ", побед " + c.wins + ", убийств " + c.kills + ", смертей " + c.deaths + ", K/D " + Stat.KD.format(c.kd()));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        StatsService stats = plugin.stats();
        if (a.length == 1) {
            out.add("top");
            if (s.hasPermission("mgp.admin")) out.add("reset");
            for (var ps : stats.all()) out.add(ps.name);
        } else if (a[0].equalsIgnoreCase("top")) {
            if (a.length == 2) out.addAll(ids());
            else if (a.length == 3) { out.add(StatsService.ALL); out.addAll(stats.modeIds()); }
        } else if (a[0].equalsIgnoreCase("reset") && s.hasPermission("mgp.admin")) {
            if (a.length == 2) for (var ps : stats.all()) out.add(ps.name);
            else if (a.length == 3) out.addAll(stats.modeIds());
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out.size() > 50 ? out.subList(0, 50) : out;
    }

    private static List<String> ids() {
        List<String> l = new ArrayList<>();
        for (Stat st : Stat.values()) l.add(st.id);
        return l;
    }

    // ------------------------------------------------------------------ /mgp lb

    public static final List<String> LB_SUBS = List.of("add", "remove", "move", "list");

    /** /mgp lb add <показатель> [режим|all] [строк] | remove [id] | move <id> | list */
    public static void leaderboard(MGPPlugin plugin, CommandSender s, String[] a) {
        Leaderboards lb = plugin.leaderboards();
        String sub = a.length >= 2 ? a[1].toLowerCase(Locale.ROOT) : "list";
        Player p = s instanceof Player pl ? pl : null;
        switch (sub) {
            case "add" -> {
                if (p == null) { Msg.send(s, "Только в игре."); return; }
                Stat st = a.length >= 3 ? Stat.parse(a[2]) : null;
                if (st == null) { Msg.send(s, "<red>/mgp lb add <" + String.join("|", ids()) + "> [режим|all] [строк 1-20]"); return; }
                String mode = a.length >= 4 ? a[3] : StatsService.ALL;
                if (!mode.equals(StatsService.ALL) && plugin.registry().mode(mode) == null) { Msg.send(s, "<red>Нет режима " + Msg.esc(mode)); return; }
                int size = 10;
                if (a.length >= 5) try { size = Integer.parseInt(a[4]); } catch (NumberFormatException ignored) {}
                Leaderboards.Board b = lb.add(p.getLocation(), st, mode, size);
                Msg.send(s, "<green>Голограмма <yellow>" + b.id() + "</yellow> поставлена. <gray>Переставить: /mgp lb move " + b.id());
            }
            case "remove", "delete", "del" -> {
                Leaderboards.Board b = a.length >= 3 ? lb.get(a[2]) : p != null ? lb.nearest(p.getLocation(), 6) : null;
                if (b == null) { Msg.send(s, "<red>Голограмма не найдена" + (a.length >= 3 ? "" : " рядом (в 6 блоках)") + ". <gray>/mgp lb list"); return; }
                lb.remove(b.id());
                Msg.send(s, "<green>Голограмма " + b.id() + " убрана.");
            }
            case "move" -> {
                if (p == null) { Msg.send(s, "Только в игре."); return; }
                Leaderboards.Board b = a.length >= 3 ? lb.get(a[2]) : lb.nearest(p.getLocation(), 6);
                if (b == null) { Msg.send(s, "<red>/mgp lb move <id>"); return; }
                lb.move(b.id(), p.getLocation());
                Msg.send(s, "<green>Голограмма " + b.id() + " перенесена сюда.");
            }
            case "list" -> {
                if (lb.all().isEmpty()) { Msg.send(s, "<gray>Голограмм нет. <yellow>/mgp lb add wins [режим]"); return; }
                for (Leaderboards.Board b : lb.all())
                    Msg.send(s, "<yellow>" + b.id() + " <gray>" + b.stat().title + ", " + plugin.stats().modeName(b.mode())
                            + " <dark_gray>" + b.world() + " " + (int) b.x() + " " + (int) b.y() + " " + (int) b.z());
            }
            default -> Msg.send(s, "<red>/mgp lb add|remove|move|list");
        }
    }

    public static List<String> leaderboardTab(MGPPlugin plugin, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 2) out.addAll(LB_SUBS);
        else if (a.length == 3 && a[1].equalsIgnoreCase("add")) out.addAll(ids());
        else if (a.length == 4 && a[1].equalsIgnoreCase("add")) { out.add(StatsService.ALL); plugin.registry().modes().forEach(m -> out.add(m.id)); }
        else if (a.length == 5 && a[1].equalsIgnoreCase("add")) out.addAll(List.of("5", "10"));
        else if (a.length == 3 && (a[1].equalsIgnoreCase("remove") || a[1].equalsIgnoreCase("move")))
            plugin.leaderboards().all().forEach(b -> out.add(b.id()));
        return out;
    }
}
