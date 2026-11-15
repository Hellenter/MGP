package ru.mgp.shop;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.mgp.ui.Menu;
import ru.mgp.util.Items;
import ru.mgp.util.Msg;

import java.util.ArrayList;
import java.util.List;

/** /mgp shop — все магазины и создание нового. */
public final class ShopListMenu extends Menu {
    public ShopListMenu(ShopService service) {
        super(6, "<dark_gray>Магазины");
        int slot = 0;
        for (Shop s : service.all()) {
            if (slot >= 45) break;
            int products = 0;
            for (Shop.Category c : s.categories) products += c.products.size();
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + s.title);
            lore.add("<gray>Категорий: <white>" + s.categories.size() + "<gray>, товаров: <white>" + products);
            lore.add("");
            lore.add("<yellow>Клик <gray>— редактировать");
            set(slot++, Items.icon(s.categories.isEmpty() ? Material.CHEST : s.categories.get(0).icon, "<gold>" + Msg.esc(s.id), lore, false),
                    p -> new ShopEditMenu(service, s, 0).open(p));
        }
        set(49, Items.icon(Material.LIME_DYE, "<green><bold>+ Новый магазин", List.of("<gray>Введи id в чат"), false), p ->
                service.plugin().wand().ask(p, "<aqua>Id магазина <gray>(латиница, например bedwars_items)", text -> {
                    Shop s = service.create(text);
                    if (s == null) { Msg.send(p, "<red>Такой магазин уже есть или id пустой"); return; }
                    new ShopEditMenu(service, s, 0).open(p);
                }));
    }
}
