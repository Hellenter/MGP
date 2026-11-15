package ru.mgp.config;

import org.bukkit.Material;
import ru.mgp.vote.VoteType;

import java.util.List;

/**
 * Группа случайных модификаторов (время суток, погода, эффекты...).
 * selection: VOTE — игроки голосуют в лобби, RANDOM — случайно по весам, FIXED — всегда option `fixed`, OFF — выключено.
 */
public record ModifierGroup(String id, String display, Material icon, Selection selection, VoteType voteType,
                            String fixed, List<Option> options) {
    public enum Selection { VOTE, RANDOM, FIXED, OFF }

    public record Option(String id, String display, Material icon, double weight, List<String> actions) {}

    public Option option(String id) {
        for (Option o : options) if (o.id().equals(id)) return o;
        return null;
    }
}
