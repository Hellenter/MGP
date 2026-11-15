package ru.mgp.shop;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import ru.mgp.ui.Menu;
import ru.mgp.util.Items;
import ru.mgp.util.Msg;

import java.util.List;

/** Настройка товара: валюта, цена, сколько штук выдаётся. */
final class ProductMenu extends Menu {
    private static final Material[] CURRENCIES = {Material.IRON_INGOT, Material.GOLD_INGOT, Material.DIAMOND, Material.EMERALD};

    private final ShopService service;
    private final Shop shop;
    private final int cat, idx;

    ProductMenu(ShopService service, Shop shop, int cat, int idx) {
        super(6, "<dark_gray>Товар");
        this.service = service;
        this.shop = shop;
        this.cat = cat;
        this.idx = idx;
        Shop.Product pr = product();
        if (pr == null) return;

        set(4, pr.item.clone(), null);
        setClick(13, Items.icon(Material.HOPPER, "<white>Выдаётся: <aqua>" + pr.item.getAmount() + " шт.", List.of(
                "", "<yellow>ЛКМ <gray>+1  <yellow>ПКМ <gray>−1", "<yellow>Shift <gray>— по 8"), false), (p, c) -> {
            int max = pr.item.getMaxStackSize();
            pr.item.setAmount(Math.max(1, Math.min(max, pr.item.getAmount() + step(c, 1, 8))));
            save(p);
        });

        for (int i = 0; i < CURRENCIES.length; i++) {
            Material m = CURRENCIES[i];
            boolean sel = pr.currency == m;
            set(19 + i * 2, Items.icon(m, Shop.currencyColor(m) + "<bold>" + Shop.pretty(m), List.of(sel ? "<green>✔ Валюта товара" : "<yellow>Нажми — платить этим"), sel), p -> {
                pr.currency = m;
                save(p);
            });
        }
        boolean custom = java.util.Arrays.stream(CURRENCIES).noneMatch(m -> m == pr.currency);
        set(27, Items.icon(custom ? pr.currency : Material.STRUCTURE_VOID, "<white>Своя валюта" + (custom ? ": <yellow>" + Shop.pretty(pr.currency) : ""), List.of(
                "<gray>Кликни по любому предмету в своём", "<gray>инвентаре — он станет валютой"), custom), null);

        setClick(31, Items.icon(Material.GOLD_NUGGET, "<white>Цена: " + Shop.currencyColor(pr.currency) + Shop.currencyName(pr.currency, pr.price), List.of(
                "", "<yellow>ЛКМ <gray>+1  <yellow>ПКМ <gray>−1", "<yellow>Shift <gray>— по 10", "<gray>0 — бесплатно"), false), (p, c) -> {
            pr.price = Math.max(0, Math.min(10_000, pr.price + step(c, 1, 10)));
            save(p);
        });

        set(49, Items.icon(Material.ARROW, "<gray>Готово — к магазину", List.of(), false), p -> new ShopEditMenu(service, shop, cat).open(p));
        setClick(53, Items.icon(Material.LAVA_BUCKET, "<red>Убрать товар", List.of("<red>Shift+клик"), false), (p, c) -> {
            if (!c.isShiftClick()) { p.sendActionBar(Msg.parse("<red>Нажми с Shift")); return; }
            shop.categories.get(cat).products.remove(idx);
            service.save(shop);
            new ShopEditMenu(service, shop, cat).open(p);
        });
    }

    private Shop.Product product() {
        if (cat >= shop.categories.size()) return null;
        var list = shop.categories.get(cat).products;
        return idx < list.size() ? list.get(idx) : null;
    }

    @Override
    public void bottomClick(Player p, ItemStack item, ClickType type) {
        Shop.Product pr = product();
        if (pr == null) return;
        pr.currency = item.getType();
        save(p);
    }

    private void save(Player p) {
        service.save(shop);
        new ProductMenu(service, shop, cat, idx).open(p);
    }

    private static int step(ClickType c, int small, int big) {
        int v = c.isShiftClick() ? big : small;
        return c.isRightClick() ? -v : v;
    }
}
