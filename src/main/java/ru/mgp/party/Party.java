package ru.mgp.party;

import java.util.*;

/** Пати: лидер, участники (включая лидера, в порядке вступления) и приглашения с временем истечения. */
public final class Party {
    UUID leader;
    final LinkedHashSet<UUID> members = new LinkedHashSet<>();
    final Map<UUID, Long> invites = new HashMap<>();

    Party(UUID leader) {
        this.leader = leader;
        members.add(leader);
    }

    public UUID leader() { return leader; }
    public Set<UUID> members() { return Collections.unmodifiableSet(members); }
    public boolean isLeader(UUID u) { return leader.equals(u); }
    public int size() { return members.size(); }
}
