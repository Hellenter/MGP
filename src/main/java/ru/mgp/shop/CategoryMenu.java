package ru.mgp.shop;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import ru.mgp.ui.Menu;
import ru.mgp.util.Items;
import ru.mgp.util.Msg;

import java.util.Collections;
import java.util.List;

/** Категория магазина: название, иконка (клик по предмету в инвентаре), порядок, удаление. */
final class CategoryMenu extends Menu {
    private final ShopService service;
    private final Shop shop;
    private final int idx;

    CategoryMenu(ShopService service, Shop shop, int idx) {
        super(3, "<dark_gray>Категория");
        this.service = service;
        this.shop = shop;
        this.idx = idx;
        if (idx >= shop.categories.size()) return;
        Shop.Category c = shop.categories.get(idx);
        set(4, Items.icon(c.icon, c.name, List.of("<gray>Иконка: кликни по предмету в своём инвентаре"), true), null);
        set(10, Items.icon(Material.NAME_TAG, "<white>Переименовать", List.of(), false), p ->
                service.plugin().wand().ask(p, "<aqua>Название категории <gray>(например <yellow>Оружие)", text -> {
                    c.name = text.contains("<") ? text : "<yellow>" + text;
                    service.save(shop);
                    new ShopEditMenu(service, shop, idx).open(p);
                }));
        set(12, Items.icon(Material.ARROW, "<white>Левее", List.of(), false), p -> move(p, -1));
        set(14, Items.icon(Material.SPECTRAL_ARROW, "<white>Правее", List.of(), false), p -> move(p, 1));
        setClick(16, Items.icon(Material.LAVA_BUCKET, "<red>Удалить категорию", List.of("<gray>Вместе с товарами", "<red>Shift+клик"), false), (p, click) -> {
            if (!click.isShiftClick()) { p.sendActionBar(Msg.parse("<red>Нажми с Shift")); return; }
            shop.categories.remove(idx);
            service.save(shop);
            new ShopEditMenu(service, shop, Math.max(0, idx - 1)).open(p);
        });
        set(22, Items.icon(Material.OAK_DOOR, "<gray>Назад", List.of(), false), p -> new ShopEditMenu(service, shop, idx).open(p));
    }

    private void move(Player p, int d) {
        int to = idx + d;
        if (to < 0 || to >= shop.categories.size()) { new CategoryMenu(service, shop, idx).open(p); return; }
        Collections.swap(shop.categories, idx, to);
        service.save(shop);
        new CategoryMenu(service, shop, to).open(p);
    }

    @Override
    public void bottomClick(Player p, ItemStack item, ClickType type) {
        if (idx >= shop.categories.size()) return;
        shop.categories.get(idx).icon = item.getType();
        service.save(shop);
        new CategoryMenu(service, shop, idx).open(p);
    }
}
