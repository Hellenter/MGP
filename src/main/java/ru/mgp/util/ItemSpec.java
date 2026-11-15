package ru.mgp.util;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * Описание предмета из конфига. Форматы:
 *   "IRON_SWORD"   |   "ARROW 16"
 *   {material: DIAMOND_SWORD, amount: 1, name: "<aqua>Меч", lore: [...], enchants: {sharpness: 2}, unbreakable: true, slot: head}
 * slot: head/chest/legs/feet/offhand или номер слота 0-35.
 */
public final class ItemSpec {
    public final Material material;
    public final int amount;
    public final String name;
    public final List<String> lore;
    public final Map<String, Integer> enchants;
    public final boolean unbreakable;
    public final String slot;

    private ItemSpec(Material material, int amount, String name, List<String> lore,
                     Map<String, Integer> enchants, boolean unbreakable, String slot) {
        this.material = material;
        this.amount = amount;
        this.name = name;
        this.lore = lore;
        this.enchants = enchants;
        this.unbreakable = unbreakable;
        this.slot = slot;
    }

    public static ItemSpec parse(Object o) {
        if (o == null) throw new IllegalArgumentException("Пустой предмет");
        Map<String, Object> m = Cfg.asMap(o);
        if (m == null) {
            String[] p = String.valueOf(o).trim().split("\\s+");
            Material mat = Material.matchMaterial(p[0]);
            if (mat == null) throw new IllegalArgumentException("Неизвестный материал: " + p[0]);
            int amount = p.length > 1 ? Integer.parseInt(p[1]) : 1;
            return new ItemSpec(mat, amount, null, List.of(), Map.of(), false, null);
        }
        Material mat = Material.matchMaterial(Cfg.str(m, "material", "STONE"));
        if (mat == null) throw new IllegalArgumentException("Неизвестный материал: " + m.get("material"));
        Map<String, Integer> ench = new LinkedHashMap<>();
        Map<String, Object> em = Cfg.asMap(m.get("enchants"));
        if (em != null) em.forEach((k, v) -> ench.put(k.toLowerCase(Locale.ROOT), Integer.parseInt(String.valueOf(v))));
        return new ItemSpec(mat, Cfg.integer(m, "amount", 1), (String) m.get("name"),
                Cfg.strings(m.get("lore")), ench, Cfg.bool(m, "unbreakable", false), (String) m.get("slot"));
    }

    public ItemStack build() { return build(amount); }

    public ItemStack build(int count) {
        ItemStack stack = new ItemStack(material, Math.max(1, count));
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        if (name != null) meta.displayName(Msg.item(name));
        if (!lore.isEmpty()) meta.lore(lore.stream().map(Msg::item).toList());
        if (unbreakable) meta.setUnbreakable(true);
        var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
        for (Map.Entry<String, Integer> e : enchants.entrySet()) {
            Enchantment en = registry.get(NamespacedKey.minecraft(e.getKey()));
            if (en != null) meta.addEnchant(en, e.getValue(), true);
        }
        stack.setItemMeta(meta);
        return stack;
    }
}
