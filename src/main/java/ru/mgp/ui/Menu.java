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
    private final Map<Integer, java.util.function.BiConsumer<Player, org.bukkit.event.inventory.ClickType>> actions = new HashMap<>();

    protected Menu(int rows, String title) {
        this.inventory = Bukkit.createInventory(this, Math.max(1, Math.min(6, rows)) * 9, Msg.parse(title));
    }

    protected void set(int slot, ItemStack item, Consumer<Player> action) {
        inventory.setItem(slot, item);
        if (action != null) actions.put(slot, (p, c) -> action.accept(p));
        else actions.remove(slot);
    }

    /** То же, но действию важен тип клика (ЛКМ, ПКМ, Shift). */
    protected void setClick(int slot, ItemStack item, java.util.function.BiConsumer<Player, org.bukkit.event.inventory.ClickType> action) {
        inventory.setItem(slot, item);
        if (action != null) actions.put(slot, action);
        else actions.remove(slot);
    }

    public void open(Player p) { p.openInventory(inventory); }

    public void click(Player p, int slot) { click(p, slot, org.bukkit.event.inventory.ClickType.LEFT); }

    public void click(Player p, int slot, org.bukkit.event.inventory.ClickType type) {
        var a = actions.get(slot);
        if (a != null) a.accept(p, type);
    }

    /** Клик по предмету в своём инвентаре (нижняя часть), пока меню открыто. По умолчанию ничего. */
    public void bottomClick(Player p, ItemStack item, org.bukkit.event.inventory.ClickType type) {}

    @Override
    public Inventory getInventory() { return inventory; }

    protected static int rowsFor(int items) { return Math.max(1, (items + 8) / 9); }
}
