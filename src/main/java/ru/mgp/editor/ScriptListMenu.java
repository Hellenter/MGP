package ru.mgp.editor;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.mgp.config.MapConfig;
import ru.mgp.ui.Menu;
import ru.mgp.util.Items;
import ru.mgp.util.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Все скрипты карты: ЛКМ — настроить, ПКМ — телепортироваться к блоку. */
final class ScriptListMenu extends Menu {
    ScriptListMenu(ScriptWand wand, Player viewer, String mapId) {
        super(6, "<dark_gray>Скрипты карты " + mapId);
        MapConfig m = wand.plugin().registry().map(mapId);
        List<Map<String, Object>> list = m == null ? List.of() : ScriptStore.cached(m);
        int slot = 0;
        for (Map<String, Object> sc : list) {
            if (slot >= 45) break;
            String id = String.valueOf(sc.get("id"));
            String type = ScriptStore.str(sc, "type", "button");
            int[] b = ScriptStore.block(sc);
            List<String> acts = ScriptStore.actions(sc);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + ScriptWand.typeName(type) + ("hold".equalsIgnoreCase(type) ? " " + ScriptWand.fmt(ScriptStore.num(sc, "hold", 3)) + " с" : ""));
            ru.mgp.util.Region ar = "area".equalsIgnoreCase(type) ? ScriptStore.area(sc) : null;
            lore.add(ar != null ? "<gray>Область: <white>" + ScriptStore.size(ar) + " <dark_gray>" + ar.minX() + " " + ar.minY() + " " + ar.minZ()
                    : "<gray>Блок: <white>" + (b == null ? "?" : b[0] + " " + b[1] + " " + b[2]));
            lore.add("");
            if (acts.isEmpty()) lore.add("<red>Нет действий");
            for (int i = 0; i < Math.min(5, acts.size()); i++) lore.add("<white>" + (i + 1) + ". " + ActionInfo.title(acts.get(i)));
            if (acts.size() > 5) lore.add("<gray>... ещё " + (acts.size() - 5));
            lore.add("");
            lore.add("<yellow>ЛКМ <gray>— настроить");
            lore.add(ar != null ? "<yellow>ПКМ <gray>— телепорт в зону" : "<yellow>ПКМ <gray>— телепорт к блоку");
            Material icon = acts.isEmpty() ? Material.GRAY_DYE : acts.get(0).isEmpty() ? Material.PAPER : ActionInfo.icon(acts.get(0));
            setClick(slot++, Items.icon(icon, "<gold>" + Msg.esc(id), lore, false), (p, c) -> {
                if (c.isRightClick() && b != null) {
                    p.closeInventory();
                    p.teleport(new Location(p.getWorld(), b[0] + 0.5, b[1] + ("area".equalsIgnoreCase(type) ? 0 : 1), b[2] + 0.5, p.getLocation().getYaw(), p.getLocation().getPitch()));
                    p.setFallDistance(0);
                } else {
                    wand.openEditor(p, mapId, id);
                }
            });
        }
        if (list.isEmpty()) set(22, Items.icon(Material.BLAZE_ROD, "<gray>Скриптов пока нет", List.of(
                "<gray>Возьми палочку и нажми <white>ЛКМ</white> по блоку,", "<gray>например по железному люку."), false), null);
        set(49, Items.icon(Material.BARRIER, "<gray>Закрыть", List.of(), false), Player::closeInventory);
    }
}
