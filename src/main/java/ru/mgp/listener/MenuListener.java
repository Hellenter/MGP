package ru.mgp.listener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import ru.mgp.MGPPlugin;
import ru.mgp.ui.Menu;

public final class MenuListener implements Listener {
    private final MGPPlugin plugin;

    public MenuListener(MGPPlugin plugin) { this.plugin = plugin; }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Menu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() == null) return;
        if (e.getClickedInventory() != top) {
            ItemStack it = e.getCurrentItem();
            if (it != null && !it.getType().isAir()) {
                ItemStack copy = it.clone();
                var t = e.getClick();
                Bukkit.getScheduler().runTask(plugin, () -> menu.bottomClick(p, copy, t));
            }
            return;
        }
        int slot = e.getRawSlot();
        var type = e.getClick();
        // открываем новые меню на следующем тике — так безопаснее, чем внутри события клика
        Bukkit.getScheduler().runTask(plugin, () -> menu.click(p, slot, type));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }
}
