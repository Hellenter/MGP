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
 * data: предмет целиком (зачарования, имя, компоненты) в Base64 — так сохраняет /mgp kit save; material и amount рядом для наглядности.
 * explosive: true | <тип> — разрывная стрела (взрыв настраивается в settings.explosive-arrows режима).
 *   Коротко: "EXPLOSIVE_ARROW 16" — 16 разрывных стрел.
 */
public final class ItemSpec {
    public final Material material;
    public final int amount;
    public final String name;
    public final List<String> lore;
    public final Map<String, Integer> enchants;
    public final boolean unbreakable;
    public final String slot;
    /** Предмет целиком (ItemStack.serializeAsBytes в Base64) или null. */
    public final String data;
    /** Профиль разрывной стрелы (default или имя из explosive-arrows.types) или null. */
    public final String explosive;
    public static final String EXPLOSIVE_ARROW = "EXPLOSIVE_ARROW";

    private ItemSpec(Material material, int amount, String name, List<String> lore,
                     Map<String, Integer> enchants, boolean unbreakable, String slot) {
        this(material, amount, name, lore, enchants, unbreakable, slot, null, null);
    }

    private ItemSpec(Material material, int amount, String name, List<String> lore,
                     Map<String, Integer> enchants, boolean unbreakable, String slot, String data, String explosive) {
        this.explosive = explosive;
        this.data = data == null || data.isBlank() ? null : data.trim();
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
            if (p[0].equalsIgnoreCase(EXPLOSIVE_ARROW)) {
                int amount = p.length > 1 ? Integer.parseInt(p[1]) : 1;
                return new ItemSpec(Material.ARROW, amount, null, List.of(), Map.of(), false, null, null, p.length > 2 ? p[2] : "default");
            }
            Material mat = Material.matchMaterial(p[0]);
            if (mat == null) throw new IllegalArgumentException("Неизвестный материал: " + p[0]);
            int amount = p.length > 1 ? Integer.parseInt(p[1]) : 1;
            return new ItemSpec(mat, amount, null, List.of(), Map.of(), false, null);
        }
        String matName = Cfg.str(m, "material", "STONE");
        String explosive = explosiveType(m.get("explosive"));
        if (matName.equalsIgnoreCase(EXPLOSIVE_ARROW)) { matName = "ARROW"; if (explosive == null) explosive = "default"; }
        Material mat = Material.matchMaterial(matName);
        if (mat == null) throw new IllegalArgumentException("Неизвестный материал: " + m.get("material"));
        Map<String, Integer> ench = new LinkedHashMap<>();
        Map<String, Object> em = Cfg.asMap(m.get("enchants"));
        if (em != null) em.forEach((k, v) -> ench.put(k.toLowerCase(Locale.ROOT), Integer.parseInt(String.valueOf(v))));
        Object slot = m.get("slot");
        return new ItemSpec(mat, Cfg.integer(m, "amount", 1), (String) m.get("name"),
                Cfg.strings(m.get("lore")), ench, Cfg.bool(m, "unbreakable", false), slot == null ? null : String.valueOf(slot),
                (String) m.get("data"), explosive);
    }

    private static String explosiveType(Object v) {
        if (v == null || v instanceof Boolean b && !b) return null;
        String s = String.valueOf(v).trim();
        if (s.isEmpty() || s.equalsIgnoreCase("false") || s.equalsIgnoreCase("no")) return null;
        return s.equalsIgnoreCase("true") || s.equalsIgnoreCase("yes") ? "default" : s;
    }

    /** Разрывные стрелы для выдачи командой: имя, описание, свечение и метка. */
    public static ItemStack explosiveArrow(int count, String type) {
        return new ItemSpec(Material.ARROW, Math.max(1, count), null, List.of(), Map.of(), false, null, null,
                type == null || type.isBlank() ? "default" : type).build();
    }

    /** Запись предмета для yml: slot, material, amount и сам предмет целиком (data). */
    public static Map<String, Object> toMap(ItemStack it, String slot) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slot", slot);
        m.put("material", it.getType().name());
        m.put("amount", it.getAmount());
        m.put("data", Base64.getEncoder().encodeToString(it.serializeAsBytes()));
        return m;
    }

    public ItemStack build() { return build(amount); }

    public ItemStack build(int count) {
        if (data != null) {
            try {
                ItemStack it = ItemStack.deserializeBytes(Base64.getDecoder().decode(data));
                if (count != amount) it.setAmount(Math.max(1, count));
                return explosive != null ? Keys.explosive(it, explosive) : it;
            } catch (Exception e) {
                // повреждённые данные — собираем по material/amount ниже
            }
        }
        ItemStack stack = new ItemStack(material, Math.max(1, count));
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        String name = this.name;
        List<String> lore = this.lore;
        if (explosive != null) {
            if (name == null) name = "<red>Разрывная стрела" + ("default".equals(explosive) ? "" : " <gray>(" + explosive + ")");
            if (lore.isEmpty()) lore = List.of("<gray>Взрывается при попадании", "<gray>в блок или игрока");
            meta.setEnchantmentGlintOverride(true);
        }
        if (name != null) meta.displayName(Msg.item(name));
        if (!lore.isEmpty()) meta.lore(lore.stream().map(Msg::item).toList());
        if (unbreakable) meta.setUnbreakable(true);
        var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
        for (Map.Entry<String, Integer> e : enchants.entrySet()) {
            Enchantment en = registry.get(NamespacedKey.minecraft(e.getKey()));
            if (en != null) meta.addEnchant(en, e.getValue(), true);
        }
        stack.setItemMeta(meta);
        return explosive != null ? Keys.explosive(stack, explosive) : stack;
    }
}
