package ru.mgp.vote;

import org.bukkit.Material;

import java.util.*;

/** Одно голосование: за карту, тему, время суток и т.д. */
public final class Vote {
    public record Option(String id, String display, Material icon, double weight, List<String> lore) {}

    public final String key;
    public final String title;
    public final Material icon;
    public final VoteType type;
    public final List<Option> options;
    private final double weightedBase;
    private final double weightedPerVote;
    private final Map<UUID, String> votes = new HashMap<>();
    private boolean closed;
    private String result;

    public Vote(String key, String title, Material icon, VoteType type, List<Option> options,
                double weightedBase, double weightedPerVote) {
        this.key = key;
        this.title = title;
        this.icon = icon;
        this.type = type;
        this.options = List.copyOf(options);
        this.weightedBase = weightedBase;
        this.weightedPerVote = weightedPerVote;
    }

    public boolean interactive() { return type != VoteType.NONE && options.size() > 1; }
    public boolean closed() { return closed; }
    public void reopen() { closed = false; result = null; }
    public String result() { return result; }

    public boolean cast(UUID voter, String optionId) {
        if (closed || option(optionId) == null) return false;
        votes.put(voter, optionId);
        return true;
    }

    public void remove(UUID voter) { if (!closed) votes.remove(voter); }
    public String votedFor(UUID voter) { return votes.get(voter); }

    public int count(String optionId) {
        int c = 0;
        for (String v : votes.values()) if (v.equals(optionId)) c++;
        return c;
    }

    public Option option(String id) {
        for (Option o : options) if (o.id().equals(id)) return o;
        return null;
    }

    /** Подводит итог и закрывает голосование. Повторный вызов возвращает тот же результат. */
    public String resolve(Random random) {
        if (result != null) return result;
        closed = true;
        if (options.isEmpty()) return null;
        result = switch (type) {
            case NONE -> weighted(random, o -> o.weight());
            case MOST_VOTES -> {
                int best = 0;
                for (Option o : options) best = Math.max(best, count(o.id()));
                if (best == 0) yield weighted(random, o -> o.weight());
                List<Option> leaders = new ArrayList<>();
                for (Option o : options) if (count(o.id()) == best) leaders.add(o);
                yield leaders.get(random.nextInt(leaders.size())).id();
            }
            case RANDOM_VOTED -> {
                List<Option> voted = new ArrayList<>();
                for (Option o : options) if (count(o.id()) > 0) voted.add(o);
                if (voted.isEmpty()) yield weighted(random, o -> o.weight());
                yield voted.get(random.nextInt(voted.size())).id();
            }
            case WEIGHTED -> weighted(random, o -> o.weight() * weightedBase + count(o.id()) * weightedPerVote);
        };
        return result;
    }

    /** Шанс победы варианта в процентах (для отображения в меню при WEIGHTED). */
    public double chance(String optionId) {
        double total = 0, mine = 0;
        for (Option o : options) {
            double w = o.weight() * weightedBase + count(o.id()) * weightedPerVote;
            total += w;
            if (o.id().equals(optionId)) mine = w;
        }
        return total <= 0 ? 0 : mine * 100.0 / total;
    }

    private String weighted(Random random, java.util.function.ToDoubleFunction<Option> weightFn) {
        double total = 0;
        for (Option o : options) total += Math.max(0, weightFn.applyAsDouble(o));
        if (total <= 0) return options.get(random.nextInt(options.size())).id();
        double r = random.nextDouble() * total;
        for (Option o : options) {
            r -= Math.max(0, weightFn.applyAsDouble(o));
            if (r <= 0) return o.id();
        }
        return options.get(options.size() - 1).id();
    }
}
