package ru.mgp.util;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Метки на служебных предметах (компас меню, кровать выхода, предметы голосования и т.д.). */
public final class Keys {
    private static NamespacedKey item;
    private static NamespacedKey explosive;

    private Keys() {}

    public static void init(Plugin plugin) {
        item = new NamespacedKey(plugin, "item");
        explosive = new NamespacedKey(plugin, "explosive");
    }

    /** Пометить стрелу разрывной; type — профиль взрыва из settings.explosive-arrows (default — основной). */
    public static ItemStack explosive(ItemStack stack, String type) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        meta.getPersistentDataContainer().set(explosive, PersistentDataType.STRING, type == null || type.isBlank() ? "default" : type.trim());
        stack.setItemMeta(meta);
        return stack;
    }

    /** Профиль разрывной стрелы или null, если предмет обычный. */
    public static String explosive(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return null;
        return stack.getItemMeta().getPersistentDataContainer().get(explosive, PersistentDataType.STRING);
    }

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
