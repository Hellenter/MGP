package ru.mgp.editor;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import ru.mgp.ui.Menu;
import ru.mgp.util.Items;
import ru.mgp.util.Msg;

import java.util.*;

/** Новый скрипт на выделенном блоке: выбор, как он срабатывает. */
final class ScriptTypeMenu extends Menu {
    ScriptTypeMenu(ScriptWand wand, Player p, String mapId, Block b) {
        super(3, "<dark_gray>Новый скрипт: как срабатывает?");
        String name = b.getType().name();
        String suggested = name.contains("TRAPDOOR") ? "hold"
                : name.endsWith("_BUTTON") || name.equals("LEVER") ? "button"
                : name.endsWith("PRESSURE_PLATE") ? "plate" : "hold";
        String where = "<gray>Блок: <white>" + pretty(b.getType()) + " <dark_gray>" + b.getX() + " " + b.getY() + " " + b.getZ();
        set(4, Items.icon(b.getType().isItem() ? b.getType() : Material.STRUCTURE_VOID, "<yellow>Выделенный блок", List.of(where), false), null);
        option(wand, mapId, b, 10, "hold", Material.ENDER_EYE, "<aqua><bold>Удержание Shift", suggested, List.of(
                "<gray>Игрок подходит к блоку и держит Shift",
                "<gray>N секунд. Над хотбаром подсказка,",
                "<gray>сверху шкала прогресса, вокруг частицы.",
                "<dark_gray>Например: люк-телепорт"));
        option(wand, mapId, b, 12, "button", Material.STONE_BUTTON, "<yellow><bold>ПКМ по блоку", suggested, List.of(
                "<gray>Срабатывает, когда игрок нажимает",
                "<gray>на блок правой кнопкой.",
                "<dark_gray>Кнопка, рычаг, табличка, любой блок"));
        option(wand, mapId, b, 14, "plate", Material.STONE_PRESSURE_PLATE, "<green><bold>Наступить", suggested, List.of(
                "<gray>Срабатывает, когда игрок",
                "<gray>наступает на блок.",
                "<dark_gray>Нажимная плита, ковёр, любой блок"));
        option(wand, mapId, b, 16, "zone", Material.LIME_STAINED_GLASS, "<light_purple><bold>Зона", suggested, List.of(
                "<gray>Срабатывает, когда игрок входит",
                "<gray>в радиус вокруг блока.",
                "<dark_gray>Ловушки, сообщения при входе"));
        set(22, Items.icon(Material.BARRIER, "<red>Отмена", List.of(), false), Player::closeInventory);
    }

    private void option(ScriptWand wand, String mapId, Block b, int slot, String type, Material icon, String name, String suggested, List<String> lore) {
        List<String> l = new ArrayList<>(lore);
        l.add("");
        l.add(type.equals(suggested) ? "<gold>★ Подходит для этого блока" : "<green>▶ Выбрать");
        set(slot, Items.icon(icon, name, l, type.equals(suggested)), pl -> create(wand, pl, mapId, b, type));
    }

    static void create(ScriptWand wand, Player p, String mapId, Block b, String type) {
        String[] id = new String[1];
        boolean ok = ScriptStore.edit(wand.plugin(), mapId, list -> {
            id[0] = ScriptStore.nextId(list, type);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id[0]);
            m.put("type", type);
            m.put("block", b.getX() + " " + b.getY() + " " + b.getZ());
            if (type.equals("hold")) {
                m.put("hold", 3.0);
                m.put("hint", "<yellow>Зажми <gold><bold>SHIFT</bold></gold>, чтобы активировать");
                m.put("progress", "<aqua>Активация");
                m.put("particle", "PORTAL");
            }
            if (type.equals("button") && !b.getType().name().endsWith("_BUTTON") && !b.getType().name().equals("LEVER"))
                m.put("cancel-use", true); // чтобы ПКМ по сундуку/двери не открывал их
            m.put("actions", new ArrayList<String>());
            list.add(m);
        });
        if (!ok) { Msg.send(p, "<red>Не удалось сохранить карту " + Msg.esc(mapId)); return; }
        Msg.send(p, "<green>Скрипт <white>" + id[0] + "</white> создан. Добавь, что он делает: «Добавить действие».");
        wand.openEditor(p, mapId, id[0]);
    }

    static String pretty(Material m) {
        String s = m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
