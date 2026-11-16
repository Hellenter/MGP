package ru.mgp.config;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import ru.mgp.util.ItemSpec;

import java.util.*;

/**
 * Таблица лута для одного тира (типа) контейнеров. Два вида:
 *   items   — случайные предметы в случайных слотах (rolls штук), как в голодных играх;
 *   presets — готовые раскладки («военные ящики»): выбирается одна раскладка по весу, предметы кладутся
 *             ровно в свои слоты (например, 3 M16 в слоты 11, 13, 15). Если presets заданы, items не используются.
 * title — имя контейнера при заполнении (видно в заголовке окна), null — не менять.
 */
public record LootTable(String id, int minRolls, int maxRolls, List<Entry> entries, List<Limit> limits,
                        List<Preset> presets, String title) {
    public record Entry(ItemSpec item, int weight, int minAmount, int maxAmount) {}

    /** Не больше max штук предметов из группы на один контейнер (loot-limits). */
    public record Limit(Set<Material> materials, int max) {}

    /** Предмет раскладки: что и сколько (amount может быть диапазоном). */
    public record Slot(ItemSpec item, int minAmount, int maxAmount) {}

    /** Раскладка ящика: слот -> предмет. */
    public record Preset(String name, int weight, Map<Integer, Slot> slots) {}

    public LootTable(String id, int minRolls, int maxRolls, List<Entry> entries) {
        this(id, minRolls, maxRolls, entries, List.of(), List.of(), null);
    }

    public LootTable(String id, int minRolls, int maxRolls, List<Entry> entries, List<Limit> limits) {
        this(id, minRolls, maxRolls, entries, limits, List.of(), null);
    }

    public void fill(Inventory inv, Random random) {
        if (!presets.isEmpty()) {
            fillPreset(inv, pickPreset(random), random);
            return;
        }
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

    /** Раскладка по весу (вес 0 — никогда). */
    public Preset pickPreset(Random random) {
        int total = 0;
        for (Preset p : presets) total += Math.max(0, p.weight());
        if (total <= 0) return presets.getFirst();
        int r = random.nextInt(total);
        for (Preset p : presets) {
            r -= Math.max(0, p.weight());
            if (r < 0) return p;
        }
        return presets.getLast();
    }

    /** Кладёт раскладку в контейнер. Слоты за пределами контейнера (например, 27+ у одинарного сундука) пропускаются. */
    public static void fillPreset(Inventory inv, Preset p, Random random) {
        inv.clear();
        for (Map.Entry<Integer, Slot> e : p.slots().entrySet()) {
            int slot = e.getKey();
            if (slot < 0 || slot >= inv.getSize()) continue;
            Slot s = e.getValue();
            int amount = s.minAmount() + (s.maxAmount() > s.minAmount() ? random.nextInt(s.maxAmount() - s.minAmount() + 1) : 0);
            if (amount > 0) inv.setItem(slot, s.item().build(amount));
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
