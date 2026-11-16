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

    /** Скрыть ники над головами у перечисленных игроков (пустой список — показать всех). */
    public void hideNametags(java.util.Collection<String> names) {
        org.bukkit.scoreboard.Team t = board.getTeam("mgp_nt");
        if (names.isEmpty()) {
            if (t != null) t.unregister();
            return;
        }
        if (t == null) {
            t = board.registerNewTeam("mgp_nt");
            t.setOption(org.bukkit.scoreboard.Team.Option.NAME_TAG_VISIBILITY, org.bukkit.scoreboard.Team.OptionStatus.NEVER);
            t.setOption(org.bukkit.scoreboard.Team.Option.COLLISION_RULE, org.bukkit.scoreboard.Team.OptionStatus.NEVER);
        }
        for (String e : new java.util.ArrayList<>(t.getEntries())) if (!names.contains(e)) t.removeEntry(e);
        for (String n : names) if (!t.hasEntry(n)) t.addEntry(n);
    }

    /** Цвета ников по командам (осада и т.п.): отдельная команда скорборда на каждый цвет. */
    public void teamColors(java.util.Map<String, net.kyori.adventure.text.format.NamedTextColor> colors) {
        for (org.bukkit.scoreboard.Team t : new java.util.ArrayList<>(board.getTeams())) {
            if (!t.getName().startsWith("mgp_c_")) continue;
            for (String e : new java.util.ArrayList<>(t.getEntries()))
                if (!t.getName().equals(colorTeam(colors.get(e)))) t.removeEntry(e);
            if (t.getEntries().isEmpty()) t.unregister();
        }
        for (var e : colors.entrySet()) {
            String name = colorTeam(e.getValue());
            org.bukkit.scoreboard.Team t = board.getTeam(name);
            if (t == null) {
                t = board.registerNewTeam(name);
                t.color(e.getValue());
            }
            if (!t.hasEntry(e.getKey())) t.addEntry(e.getKey());
        }
    }

    private static String colorTeam(net.kyori.adventure.text.format.NamedTextColor c) {
        return c == null ? "" : "mgp_c_" + net.kyori.adventure.text.format.NamedTextColor.NAMES.key(c);
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
