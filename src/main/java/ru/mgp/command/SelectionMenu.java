package ru.mgp.command;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import ru.mgp.ui.Menu;
import ru.mgp.util.Items;
import ru.mgp.util.Msg;
import ru.mgp.util.Pos;

import java.util.List;

/** /mgp setup sel — выделение как параллелепипед: каждая грань двигается отдельно, рамка обновляется сразу. */
final class SelectionMenu extends Menu {
    private static final Material[] ICONS = {
            Material.RED_CONCRETE, Material.ORANGE_CONCRETE,   // X+ X−
            Material.LIME_CONCRETE, Material.GREEN_CONCRETE,   // Y+ Y−
            Material.LIGHT_BLUE_CONCRETE, Material.BLUE_CONCRETE // Z+ Z−
    };
    /** Слоты граней: как на развёртке — верх над центром, низ под ним, стороны света по бокам. */
    private static final int[] SLOTS = {24, 20, 13, 31, 23, 21}; // east, west, up, down, south, north

    SelectionMenu(SelectionOps ops, Player viewer) {
        super(5, "<dark_gray>Выделение: грани");
        if (!ops.has(viewer)) return;
        for (int i = 0; i < 6; i++) {
            SelectionOps.Face f = SelectionOps.FACES.get(i);
            int v = ops.face(viewer, f);
            setClick(SLOTS[i], Items.icon(ICONS[i], "<white>" + cap(f.label()) + ": <yellow>" + v, List.of(
                    "<gray>/mgp setup face " + f.id() + " ...",
                    "",
                    "<green>ЛКМ <gray>— наружу на 1, <green>Shift+ЛКМ <gray>— на 10",
                    "<red>ПКМ <gray>— внутрь на 1, <red>Shift+ПКМ <gray>— на 10",
                    "<aqua>Q <gray>— поставить грань туда, где ты стоишь"), false), (p, c) -> {
                if (!ops.has(p)) { p.closeInventory(); return; }
                if (c == ClickType.DROP || c == ClickType.CONTROL_DROP) {
                    Pos me = Pos.ofBlock(p.getLocation());
                    ops.setFace(p, f, f.axis() == 0 ? me.bx() : f.axis() == 1 ? me.by() : me.bz());
                } else {
                    int n = c.isShiftClick() ? 10 : 1;
                    if (c.isLeftClick()) ops.moveFace(p, f, n);
                    else if (c.isRightClick()) ops.moveFace(p, f, -n);
                    else return;
                }
                ops.refresh(p);
                p.sendActionBar(Msg.parse("<gray>" + cap(f.label()) + ": <yellow>" + ops.face(p, f) + "  <gray>" + ops.size(p)));
                new SelectionMenu(ops, p).open(p);
            });
        }
        set(22, Items.icon(Material.GLASS, "<white>Размер: <yellow>" + ops.size(viewer), List.of(
                "<gray>X: " + ops.face(viewer, SelectionOps.FACES.get(1)) + " .. " + ops.face(viewer, SelectionOps.FACES.get(0)),
                "<gray>Y: " + ops.face(viewer, SelectionOps.FACES.get(3)) + " .. " + ops.face(viewer, SelectionOps.FACES.get(2)),
                "<gray>Z: " + ops.face(viewer, SelectionOps.FACES.get(5)) + " .. " + ops.face(viewer, SelectionOps.FACES.get(4))), true), null);

        set(36, Items.icon(Material.LADDER, "<white>На всю высоту мира", List.of("<gray>/mgp setup expand vert"), false), p -> run(ops, p, "expand", "vert"));
        setClick(38, Items.icon(Material.SLIME_BALL, "<white>Во все стороны", List.of("<green>ЛКМ <gray>+1 <gray>(outset)", "<red>ПКМ <gray>−1 <gray>(inset)", "<gray>Shift — по 10"), false),
                (p, c) -> run(ops, p, c.isRightClick() ? "inset" : "outset", c.isShiftClick() ? "10" : "1"));
        setClick(40, Items.icon(Material.MAGMA_CREAM, "<white>По горизонтали", List.of("<green>ЛКМ <gray>+1, <red>ПКМ <gray>−1", "<gray>Shift — по 10", "<gray>(outset -h)"), false),
                (p, c) -> run(ops, p, c.isRightClick() ? "inset" : "outset", c.isShiftClick() ? "10" : "1", "-h"));
        setClick(42, Items.icon(Material.COMPASS, "<white>Сдвинуть всю рамку", List.of("<gray>туда, куда смотришь", "<green>ЛКМ <gray>на 1, <green>Shift <gray>на 10", "<red>ПКМ <gray>— назад"), false),
                (p, c) -> run(ops, p, "shift", c.isShiftClick() ? "10" : "1", c.isRightClick() ? "back" : "me"));
        if (ops.makeTrigger != null)
            set(8, Items.icon(Material.TRIPWIRE_HOOK, "<light_purple><bold>Сделать зону-триггер", List.of(
                    "<gray>Из этого выделения: срабатывает,",
                    "<gray>когда игрок входит, стоит внутри",
                    "<gray>или выходит (телепорт, сообщение...).",
                    "<dark_gray>/mgp setup trigger [id]"), true), p -> ops.makeTrigger.run(p));
        set(44, Items.icon(Material.BOOK, "<aqua>Команды", List.of(
                "<yellow>/mgp setup expand <n> [обратно] [напр.]",
                "<yellow>/mgp setup contract <n> [обратно] [напр.]",
                "<yellow>/mgp setup shift <n> [напр.]",
                "<yellow>/mgp setup outset|inset <n> [-h|-v]",
                "<yellow>/mgp setup face <грань> <коорд.|+n|-n|here>",
                "<yellow>/mgp setup box x1 y1 z1 x2 y2 z2",
                "<gray>Направления: me back left right up down",
                "<gray>north south east west"), false), null);
    }

    private static void run(SelectionOps ops, Player p, String... args) {
        ops.handle(p, args);
        new SelectionMenu(ops, p).open(p);
    }

    private static String cap(String s) { return Character.toUpperCase(s.charAt(0)) + s.substring(1); }
}
