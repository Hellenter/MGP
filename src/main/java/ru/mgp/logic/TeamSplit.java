package ru.mgp.logic;

import org.bukkit.entity.Player;
import ru.mgp.party.Party;
import ru.mgp.session.GameSession;

import java.util.*;

/** Деление игроков на две команды (защита / атака) в нужной пропорции; пати всегда остаются вместе. */
final class TeamSplit {
    private TeamSplit() {}

    /** Кто идёт в защиту: примерно ratio от всех (минимум 1 и хотя бы 1 атакующий). */
    static Set<UUID> defenders(GameSession session, List<Player> all, double ratio) {
        Map<Object, List<Player>> groups = new LinkedHashMap<>();
        for (Player p : all) {
            Party party = session.plugin().parties().of(p);
            groups.computeIfAbsent(party != null ? party : p.getUniqueId(), k -> new ArrayList<>()).add(p);
        }
        List<List<Player>> gl = new ArrayList<>(groups.values());
        Collections.shuffle(gl, session.random());
        gl.sort(Comparator.comparingInt((List<Player> g) -> -g.size()));
        int want = Math.max(1, Math.min(all.size() - 1, (int) Math.round(all.size() * ratio)));
        Set<UUID> def = new HashSet<>();
        for (List<Player> g : gl) {
            boolean toDef = def.size() < want && (def.size() + g.size() <= want || def.isEmpty());
            if (toDef) for (Player p : g) def.add(p.getUniqueId());
        }
        // все в одной команде (одна большая пати) — делим поровну, иначе играть некому
        if (def.isEmpty() || def.size() == all.size()) {
            def.clear();
            for (int i = 0; i < want; i++) def.add(all.get(i).getUniqueId());
        }
        return def;
    }
}
