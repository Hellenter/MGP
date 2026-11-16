package ru.mgp.shop;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ru.mgp.ui.Menu;
import ru.mgp.util.Items;
import ru.mgp.util.Msg;

import java.util.ArrayList;
import java.util.List;

/**
 * Витрина магазина, как в Bed Wars: верхний ряд — категории, под выбранной зелёное стекло,
 * товары в середине, в описании цена и сколько не хватает. Клик — купить.
 */
public class ShopMenu extends Menu {
    /** Слоты товаров: середина сундука без краёв (как у Hypixel). */
    public static final int[] SLOTS = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    protected final ShopService service;
    protected final Shop shop;
    protected final int cat;

    public ShopMenu(ShopService service, Shop shop, int cat) {
        this(service, shop, cat, shop.title);
    }

    protected ShopMenu(ShopService service, Shop shop, int cat, String title) {
        super(6, title);
        this.service = service;
        this.shop = shop;
        this.cat = Math.max(0, Math.min(cat, shop.categories.size() - 1));
    }

    @Override
    public void open(Player p) {
        build(p);
        super.open(p);
    }

    protected void build(Player p) {
        for (int i = 0; i < Math.min(9, shop.categories.size()); i++) {
            Shop.Category c = shop.categories.get(i);
            int idx = i;
            set(i, Items.icon(c.icon, c.name, List.of(i == cat ? "<green>Выбрано" : "<yellow>Нажми, чтобы открыть"), i == cat),
                    pl -> reopen(pl, idx));
        }
        for (int i = 9; i < 18; i++)
            set(i, Items.icon(i - 9 == cat ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE,
                    i - 9 == cat ? "<green>⬆ " + (cat < shop.categories.size() ? shop.categories.get(cat).name : "") : "<dark_gray>⬆ Категории", List.of(), false), null);
        if (shop.categories.isEmpty()) return;
        Shop.Category c = shop.categories.get(cat);
        for (int i = 0; i < Math.min(SLOTS.length, c.products.size()); i++) {
            Shop.Product pr = c.products.get(i);
            set(SLOTS[i], display(p, pr), pl -> {
                if (service.buy(pl, pr)) reopen(pl, cat);
            });
        }
    }

    protected void reopen(Player p, int category) {
        new ShopMenu(service, shop, category).open(p);
    }

    /** Предмет витрины: сам товар + цена и подсказка «купить» / «не хватает». */
    protected ItemStack display(Player p, Shop.Product pr) {
        ItemStack it = pr.item.clone();
        ItemMeta meta = it.getItemMeta();
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        if (meta.lore() != null) lore.addAll(meta.lore());
        lore.add(Msg.item(""));
        lore.add(Msg.item("<gray>Цена: " + pr.priceColor() + pr.priceText(pr.price)));
        lore.add(Msg.item(""));
        int have = p == null ? 0 : service.balance(p, pr);
        if (pr.money && have >= 0) lore.add(Msg.item("<gray>На счёте: <gold>" + Shop.moneyName(have)));
        lore.add(Msg.item(have >= pr.price ? "<yellow>Нажми, чтобы купить!" : have < 0 ? "<red>Монеты счёта — только в игре с экономикой"
                : "<red>Не хватает " + pr.priceText(pr.price - have)));
        meta.lore(lore);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }
}
