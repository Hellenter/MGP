package ru.mgp.config;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import ru.mgp.util.ItemSpec;

import java.util.*;

/** Таблица лута для одного тира сундуков. */
public record LootTable(String id, int minRolls, int maxRolls, List<Entry> entries, List<Limit> limits) {
    public record Entry(ItemSpec item, int weight, int minAmount, int maxAmount) {}

    /** Не больше max штук предметов из группы на один контейнер (loot-limits). */
    public record Limit(Set<Material> materials, int max) {}

    public LootTable(String id, int minRolls, int maxRolls, List<Entry> entries) {
        this(id, minRolls, maxRolls, entries, List.of());
    }

    public void fill(Inventory inv, Random random) {
        inv.clear();
        if (entries.isEmpty()) return;
        int total = 0;
        for (Entry e : entries) total += Math.max(0, e.weight());
        if (total <= 0) return;
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) slots.add(i);
        Collections.shuffle(slots, random);
        int rolls = minRolls + (maxRolls > minRolls ? random.nextInt(maxRolls - minRolls + 1) : 0);
        rolls = Math.min(rolls, slots.size());
        int[] used = new int[limits.size()];
        for (int i = 0; i < rolls; i++) {
            // предмет из исчерпанной группы — перебрасываем (несколько попыток, потом слот пустой)
            for (int attempt = 0; attempt < 10; attempt++) {
                Entry e = pick(random, total);
                int amount = e.minAmount() + (e.maxAmount() > e.minAmount() ? random.nextInt(e.maxAmount() - e.minAmount() + 1) : 0);
                Material mat = e.item().material;
                for (int l = 0; l < limits.size(); l++) {
                    if (limits.get(l).materials().contains(mat)) amount = Math.min(amount, limits.get(l).max() - used[l]);
                }
                if (amount <= 0) continue;
                for (int l = 0; l < limits.size(); l++) {
                    if (limits.get(l).materials().contains(mat)) used[l] += amount;
                }
                inv.setItem(slots.get(i), e.item().build(amount));
                break;
            }
        }
    }

    private Entry pick(Random random, int total) {
        int r = random.nextInt(total);
        for (Entry e : entries) {
            r -= Math.max(0, e.weight());
            if (r < 0) return e;
        }
        return entries.getLast();
    }
}
