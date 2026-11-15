package ru.mgp.shop;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ru.mgp.config.MapConfig;
import ru.mgp.ui.Menu;
import ru.mgp.util.Items;
import ru.mgp.util.Msg;
import ru.mgp.util.Pos;

import java.util.*;

/**
 * Редактор магазина в игре: та же витрина, но клики меняют её.
 * Товар добавляется кликом по предмету в своём инвентаре; цена — в меню товара.
 */
public final class ShopEditMenu extends ShopMenu {
    static final List<String> PROFESSIONS = List.of("weaponsmith", "armorer", "toolsmith", "librarian", "cleric", "farmer",
            "butcher", "fletcher", "cartographer", "leatherworker", "mason", "shepherd", "fisherman", "nitwit", "none");

    public ShopEditMenu(ShopService service, Shop shop, int cat) {
        super(service, shop, cat, "<dark_gray>Редактор магазина: " + shop.id);
    }

    @Override
    protected void reopen(Player p, int category) {
        new ShopEditMenu(service, shop, category).open(p);
    }

    private void saveAndReopen(Player p, int category) {
        if (!service.save(shop)) Msg.send(p, "<red>Не удалось сохранить магазин — смотри консоль");
        reopen(p, category);
    }

    @Override
    protected void build(Player p) {
        // категории: ЛКМ — открыть, ПКМ — настроить
        for (int i = 0; i < Math.min(9, shop.categories.size()); i++) {
            Shop.Category c = shop.categories.get(i);
            int idx = i;
            setClick(i, Items.icon(c.icon, c.name, List.of(i == cat ? "<green>Открыта" : "<yellow>ЛКМ <gray>— открыть",
                    "<yellow>ПКМ <gray>— название, иконка, порядок, удалить"), i == cat), (pl, click) -> {
                if (click.isRightClick()) new CategoryMenu(service, shop, idx).open(pl);
                else reopen(pl, idx);
            });
        }
        if (shop.categories.size() < 9) {
            set(shop.categories.size(), Items.icon(Material.LIME_DYE, "<green><bold>+ Категория", List.of("<gray>Например: Блоки, Оружие, Броня"), false), pl ->
                    service.plugin().wand().ask(pl, "<aqua>Название категории <gray>(можно с цветом: <yellow>Блоки)", text -> {
                        shop.categories.add(new Shop.Category(text.contains("<") ? text : "<yellow>" + text, Material.CHEST));
                        saveAndReopen(pl, shop.categories.size() - 1);
                    }));
        }
        for (int i = 9; i < 18; i++)
            set(i, Items.icon(i - 9 == cat ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE, "<dark_gray>⬆ Категории", List.of(), false), null);

        Shop.Category c = shop.categories.isEmpty() ? null : shop.categories.get(cat);
        for (int i = 0; i < SLOTS.length; i++) {
            int idx = i;
            if (c != null && i < c.products.size()) {
                Shop.Product pr = c.products.get(i);
                ItemStack it = display(null, pr);
                ItemMeta meta = it.getItemMeta();
                List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
                lore.add(Msg.item("<gray>Цена: " + Shop.currencyColor(pr.currency) + Shop.currencyName(pr.currency, pr.price)));
                lore.add(Msg.item(""));
                lore.add(Msg.item("<yellow>ЛКМ <gray>— цена и количество"));
                lore.add(Msg.item("<yellow>Shift+ЛКМ <gray>— левее"));
                lore.add(Msg.item("<red>Shift+ПКМ <gray>— убрать товар"));
                meta.lore(lore);
                it.setItemMeta(meta);
                setClick(SLOTS[i], it, (pl, click) -> {
                    if (click == ClickType.SHIFT_RIGHT) { c.products.remove(idx); saveAndReopen(pl, cat); }
                    else if (click == ClickType.SHIFT_LEFT) { if (idx > 0) Collections.swap(c.products, idx, idx - 1); saveAndReopen(pl, cat); }
                    else new ProductMenu(service, shop, cat, idx).open(pl);
                });
            } else {
                set(SLOTS[i], Items.icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<dark_gray>Пусто",
                        List.of("<gray>Кликни по предмету в <white>своём инвентаре</white>,", "<gray>чтобы добавить его сюда товаром"), false), null);
            }
        }

        // нижний ряд
        set(45, Items.icon(Material.BOOK, "<yellow>Как пользоваться", List.of(
                "<gray>1. Возьми нужные предметы в инвентарь", "<gray>   (из креатива, с чарами, именами — как хочешь).",
                "<gray>2. Кликни по предмету в своём инвентаре —", "<gray>   он станет товаром в открытой категории.",
                "<gray>3. Нажми на товар и выбери цену.", "<gray>4. «Поставить продавца» — там, где стоишь.",
                "", "<dark_gray>Shift+ПКМ по продавцу вне игры — этот редактор"), false), null);
        setClick(46, Items.icon(Material.NAME_TAG, "<white>Названия", List.of(
                "<gray>Окно: " + shop.title, "<gray>Над продавцом: " + shop.npcName, "",
                "<yellow>ЛКМ <gray>— название окна", "<yellow>ПКМ <gray>— имя над продавцом"), false), (pl, click) -> {
            boolean npc = click.isRightClick();
            service.plugin().wand().ask(pl, npc ? "<aqua>Имя над продавцом, например <white><yellow><bold>МАГАЗИН" : "<aqua>Название окна магазина, например <white><dark_gray>Магазин предметов", text -> {
                if (npc) shop.npcName = text; else shop.title = text;
                saveAndReopen(pl, cat);
            });
        });
        setClick(47, Items.icon(Material.VILLAGER_SPAWN_EGG, "<white>Вид продавца: <aqua>" + shop.profession, List.of("<gray>Профессия жителя", "", "<yellow>Клик <gray>— следующая"), false), (pl, click) -> {
            int i = Math.max(0, PROFESSIONS.indexOf(shop.profession));
            shop.profession = PROFESSIONS.get((i + (click.isRightClick() ? PROFESSIONS.size() - 1 : 1)) % PROFESSIONS.size());
            saveAndReopen(pl, cat);
        });
        set(49, Items.icon(Material.EMERALD, "<green><bold>Посмотреть как игрок", List.of("<gray>Открыть витрину без редактора"), false),
                pl -> new ShopMenu(service, shop, cat).open(pl));
        set(51, Items.icon(Material.ARMOR_STAND, "<gold><bold>Поставить продавца здесь", List.of(
                "<gray>Продавец появится в каждой игре на карте", "<gray>там, где ты стоишь, и будет смотреть туда же.",
                "<dark_gray>Карта определяется как у палочки скриптов"), false), pl -> placeTrader(pl));
        set(52, Items.icon(Material.COMPASS, "<white>Продавцы на этой карте", List.of(), false), pl -> {
            MapConfig m = service.plugin().wand().map(pl);
            if (m == null) { Msg.send(pl, "<red>Не понял, какая это карта. /mgp wand <карта> — закрепить"); return; }
            new TradersMenu(service, m.id, shop).open(pl);
        });
        setClick(53, Items.icon(Material.LAVA_BUCKET, "<red><bold>Удалить магазин", List.of("<red>Shift+клик <gray>— удалить навсегда"), false), (pl, click) -> {
            if (!click.isShiftClick()) { pl.sendActionBar(Msg.parse("<red>Чтобы удалить, нажми с Shift")); return; }
            service.delete(shop);
            pl.closeInventory();
            Msg.send(pl, "<green>Магазин " + shop.id + " удалён. Продавцы на картах с ним больше не появятся.");
        });
    }

    @Override
    public void bottomClick(Player p, ItemStack item, ClickType type) {
        if (shop.categories.isEmpty()) { Msg.send(p, "<red>Сначала добавь категорию"); return; }
        Shop.Category c = shop.categories.get(cat);
        if (c.products.size() >= SLOTS.length) { Msg.send(p, "<red>В категории нет места (максимум " + SLOTS.length + ")"); return; }
        c.products.add(new Shop.Product(item.clone(), Material.IRON_INGOT, 1));
        service.save(shop);
        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);
        new ProductMenu(service, shop, cat, c.products.size() - 1).open(p);
    }

    private void placeTrader(Player p) {
        MapConfig m = service.plugin().wand().map(p);
        if (m == null) { Msg.send(p, "<red>Не понял, какая это карта.</red> <gray>Встань в область карты или закрепи: /mgp wand <карта>"); return; }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(m.file);
        List<Map<?, ?>> list = new ArrayList<>(y.getMapList("traders"));
        Map<String, Object> t = new LinkedHashMap<>();
        var l = p.getLocation();
        t.put("pos", new Pos(Math.round(l.getX() * 10) / 10.0, Math.round(l.getY() * 100) / 100.0, Math.round(l.getZ() * 10) / 10.0, Math.round(l.getYaw() / 15f) * 15f, 0).serialize());
        t.put("shop", shop.id);
        t.put("name", shop.npcName);
        t.put("profession", shop.profession);
        list.add(t);
        y.set("traders", list);
        try {
            y.save(m.file);
        } catch (java.io.IOException e) {
            Msg.send(p, "<red>Не удалось сохранить карту: " + Msg.esc(e.getMessage()));
            return;
        }
        service.plugin().registry().loadMap(m.file);
        p.closeInventory();
        Msg.send(p, "<green>Продавец магазина <white>" + shop.id + "</white> поставлен на карту <white>" + m.id + "</white>. Он появится в следующей игре.");
        Msg.send(p, "<gray>Пока палочка скриптов в руке, продавцы видны как светящиеся метки.");
    }
}
