package ru.mgp.listener;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.EnchantingInventory;
import org.bukkit.inventory.ItemStack;
import ru.mgp.MGPPlugin;
import ru.mgp.session.GameSession;

/**
 * Столы зачарования без лазурита (настройка режима free-lapis, по умолчанию включена в голодных играх):
 * в слот лазурита кладётся 64 штуки, забрать их нельзя, после зачарования слот пополняется,
 * при закрытии стол очищается — лазурит не попадает в инвентарь.
 */
public final class FreeLapisListener implements Listener {
    private static final int LAPIS_SLOT = 1;
    private final MGPPlugin plugin;

    public FreeLapisListener(MGPPlugin plugin) { this.plugin = plugin; }

    private boolean enabled(Player p) {
        GameSession s = plugin.sessions().of(p);
        return s != null && s.isPlaying() && s.isAlive(p)
                && s.mode.settings.getBoolean("free-lapis", "hunger_games".equals(s.mode.type));
    }

    private static void fill(EnchantingInventory inv) {
        inv.setItem(LAPIS_SLOT, new ItemStack(Material.LAPIS_LAZULI, 64));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (e.getInventory() instanceof EnchantingInventory inv && e.getPlayer() instanceof Player p && enabled(p)) fill(inv);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory() instanceof EnchantingInventory) || !(e.getWhoClicked() instanceof Player p) || !enabled(p)) return;
        if (e.getRawSlot() == LAPIS_SLOT) { e.setCancelled(true); return; }
        // свой лазурит в стол не кладём и бесплатный не забираем двойным кликом
        if (e.getClick() == ClickType.DOUBLE_CLICK && e.getCursor() != null && e.getCursor().getType() == Material.LAPIS_LAZULI) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory() instanceof EnchantingInventory && e.getRawSlots().contains(LAPIS_SLOT)
                && e.getWhoClicked() instanceof Player p && enabled(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent e) {
        if (!(e.getInventory() instanceof EnchantingInventory inv) || !enabled(e.getEnchanter())) return;
        Bukkit.getScheduler().runTask(plugin, () -> fill(inv));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory() instanceof EnchantingInventory inv)) return;
        ItemStack l = inv.getItem(LAPIS_SLOT);
        // убираем только «бесплатный» лазурит; в обычной игре (вне сессий) стол работает как в ванили
        if (l != null && l.getType() == Material.LAPIS_LAZULI && e.getPlayer() instanceof Player p && plugin.sessions().of(p) != null
                && plugin.sessions().of(p).mode.settings.getBoolean("free-lapis", "hunger_games".equals(plugin.sessions().of(p).mode.type)))
            inv.setItem(LAPIS_SLOT, null);
    }
}
