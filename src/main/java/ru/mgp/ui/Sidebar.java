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
