package ru.mgp.shop;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ru.mgp.config.MapConfig;
import ru.mgp.ui.Menu;
import ru.mgp.util.Items;
import ru.mgp.util.Msg;
import ru.mgp.util.Pos;

import java.util.*;

/** Продавцы на карте (maps/<карта>.yml → traders): ЛКМ — телепорт, Shift+ПКМ — убрать. */
final class TradersMenu extends Menu {
    TradersMenu(ShopService service, String mapId, Shop back) {
        super(6, "<dark_gray>Продавцы: " + mapId);
        MapConfig m = service.plugin().registry().map(mapId);
        List<Map<?, ?>> list = m == null ? List.of() : m.yaml.getMapList("traders");
        for (int i = 0; i < Math.min(45, list.size()); i++) {
            Map<?, ?> t = list.get(i);
            int idx = i;
            Object shopId = t.get("shop"), set = t.get("set");
            String what = shopId != null ? "Магазин <white>" + shopId : "Сделки жителя <white>" + set;
            Pos pos;
            try { pos = Pos.parse(String.valueOf(t.get("pos"))); } catch (Exception e) { pos = null; }
            Pos fp = pos;
            setClick(i, Items.icon(shopId != null ? Material.EMERALD : Material.VILLAGER_SPAWN_EGG, "<gold>" + (t.get("name") == null ? "Продавец" : String.valueOf(t.get("name"))),
                    List.of("<gray>" + what, "<gray>" + (pos == null ? "?" : pos.serialize()), "", "<yellow>ЛКМ <gray>— телепорт", "<red>Shift+ПКМ <gray>— убрать с карты"), false), (p, c) -> {
                if (c == org.bukkit.event.inventory.ClickType.SHIFT_RIGHT) {
                    YamlConfiguration y = YamlConfiguration.loadConfiguration(m.file);
                    List<Map<?, ?>> l = new ArrayList<>(y.getMapList("traders"));
                    if (idx < l.size()) l.remove(idx);
                    y.set("traders", l.isEmpty() ? null : l);
                    try { y.save(m.file); } catch (java.io.IOException e) { Msg.send(p, "<red>" + Msg.esc(e.getMessage())); return; }
                    service.plugin().registry().loadMap(m.file);
                    new TradersMenu(service, mapId, back).open(p);
                } else if (fp != null) {
                    p.closeInventory();
                    Location l = fp.toLocation(p.getWorld());
                    p.teleport(l.add(l.getDirection().multiply(2)).setDirection(l.getDirection().multiply(-1)));
                }
            });
        }
        if (list.isEmpty()) set(22, Items.icon(Material.BARRIER, "<gray>На карте нет продавцов", List.of(), false), null);
        if (back != null) set(49, Items.icon(Material.ARROW, "<gray>Назад к магазину", List.of(), false), p -> new ShopEditMenu(service, back, 0).open(p));
    }
}
