package ru.mgp.ui;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.util.List;

/** Инфо-панель справа. Строки задаются Component'ами, числа справа скрыты. */
public final class Sidebar {
    private static final int MAX_LINES = 15;
    private final Scoreboard board;
    private final Objective objective;
    private int size;

    public Sidebar() {
        board = Bukkit.getScoreboardManager().getNewScoreboard();
        objective = board.registerNewObjective("mgp", Criteria.DUMMY, Component.empty());
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.numberFormat(NumberFormat.blank());
    }

    public Scoreboard board() { return board; }

    /**
     * Команды скорборда этого зрителя: цвет ника по команде (осада и т.п.) и скрытые ники (прятки, противники в осаде).
     * Запись может быть только в одной команде, поэтому скрытый цветной ник живёт в своей команде "mgp_c_<цвет>_h":
     * ник не виден, а цвет (и цвет свечения) остаётся.
     */
    public void teams(java.util.Map<String, net.kyori.adventure.text.format.NamedTextColor> colors, java.util.Collection<String> hidden) {
        java.util.Map<String, String> want = new java.util.HashMap<>();
        for (var e : colors.entrySet()) if (e.getValue() != null)
            want.put(e.getKey(), colorTeam(e.getValue()) + (hidden.contains(e.getKey()) ? "_h" : ""));
        for (String n : hidden) want.putIfAbsent(n, "mgp_nt");
        for (org.bukkit.scoreboard.Team t : new java.util.ArrayList<>(board.getTeams())) {
            if (!t.getName().startsWith("mgp_")) continue;
            for (String e : new java.util.ArrayList<>(t.getEntries())) if (!t.getName().equals(want.get(e))) t.removeEntry(e);
            if (t.getEntries().isEmpty()) t.unregister();
        }
        for (var e : want.entrySet()) {
            String name = e.getValue();
            org.bukkit.scoreboard.Team t = board.getTeam(name);
            if (t == null) {
                t = board.registerNewTeam(name);
                if (name.startsWith("mgp_c_")) t.color(colors.get(e.getKey()));
                if (name.equals("mgp_nt") || name.endsWith("_h"))
                    t.setOption(org.bukkit.scoreboard.Team.Option.NAME_TAG_VISIBILITY, org.bukkit.scoreboard.Team.OptionStatus.NEVER);
                if (name.equals("mgp_nt"))
                    t.setOption(org.bukkit.scoreboard.Team.Option.COLLISION_RULE, org.bukkit.scoreboard.Team.OptionStatus.NEVER);
            }
            if (!t.hasEntry(e.getKey())) t.addEntry(e.getKey());
        }
    }

    private static String colorTeam(net.kyori.adventure.text.format.NamedTextColor c) {
        return "mgp_c_" + net.kyori.adventure.text.format.NamedTextColor.NAMES.key(c);
    }

    public void update(Component title, List<Component> lines) {
        objective.displayName(title);
        int n = Math.min(lines.size(), MAX_LINES);
        for (int i = 0; i < n; i++) {
            Score score = objective.getScore("l" + i);
            score.customName(lines.get(i));
            score.setScore(n - i);
        }
        for (int i = n; i < size; i++) board.resetScores("l" + i);
        size = n;
    }
}
