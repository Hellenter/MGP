package ru.mgp.util;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Метки на служебных предметах (компас меню, кровать выхода, предметы голосования и т.д.). */
public final class Keys {
    private static NamespacedKey item;

    private Keys() {}

    public static void init(Plugin plugin) { item = new NamespacedKey(plugin, "item"); }

    public static ItemStack tag(ItemStack stack, String value) {
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(item, PersistentDataType.STRING, value);
        stack.setItemMeta(meta);
        return stack;
    }

    public static String tag(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return null;
        return stack.getItemMeta().getPersistentDataContainer().get(item, PersistentDataType.STRING);
    }
}
