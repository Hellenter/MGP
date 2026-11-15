package ru.mgp.ui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import ru.mgp.util.Msg;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Базовое GUI-меню: слот → предмет + действие по клику. */
public abstract class Menu implements InventoryHolder {
    private final Inventory inventory;
    private final Map<Integer, Consumer<Player>> actions = new HashMap<>();

    protected Menu(int rows, String title) {
        this.inventory = Bukkit.createInventory(this, Math.max(1, Math.min(6, rows)) * 9, Msg.parse(title));
    }

    protected void set(int slot, ItemStack item, Consumer<Player> action) {
        inventory.setItem(slot, item);
        if (action != null) actions.put(slot, action);
        else actions.remove(slot);
    }

    public void open(Player p) { p.openInventory(inventory); }

    public void click(Player p, int slot) {
        Consumer<Player> a = actions.get(slot);
        if (a != null) a.accept(p);
    }

    @Override
    public Inventory getInventory() { return inventory; }

    protected static int rowsFor(int items) { return Math.max(1, (items + 8) / 9); }
}
