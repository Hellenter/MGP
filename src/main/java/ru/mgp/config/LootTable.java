package ru.mgp.config;

import org.bukkit.inventory.Inventory;
import ru.mgp.util.ItemSpec;

import java.util.*;

/** Таблица лута для одного тира сундуков. */
public record LootTable(String id, int minRolls, int maxRolls, List<Entry> entries) {
    public record Entry(ItemSpec item, int weight, int minAmount, int maxAmount) {}

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
        for (int i = 0; i < rolls; i++) {
            int r = random.nextInt(total);
            for (Entry e : entries) {
                r -= Math.max(0, e.weight());
                if (r < 0) {
                    int amount = e.minAmount() + (e.maxAmount() > e.minAmount() ? random.nextInt(e.maxAmount() - e.minAmount() + 1) : 0);
                    inv.setItem(slots.get(i), e.item().build(amount));
                    break;
                }
            }
        }
    }
}
