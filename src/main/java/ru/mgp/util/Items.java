package ru.mgp.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/** Быстрое создание иконок для меню и служебных предметов. */
public final class Items {
    private Items() {}

    public static ItemStack icon(Material m, String name, List<String> lore, boolean glint) {
        ItemStack stack = new ItemStack(m);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Msg.item(name));
        if (lore != null && !lore.isEmpty()) meta.lore(lore.stream().map(Msg::item).toList());
        if (glint) meta.setEnchantmentGlintOverride(true);
        meta.addItemFlags(ItemFlag.values());
        stack.setItemMeta(meta);
        return stack;
    }

    public static ItemStack tagged(Material m, String name, String tag, String... lore) {
        return Keys.tag(icon(m, name, List.of(lore), false), tag);
    }
}
