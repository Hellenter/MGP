package ru.mgp.ui;

import ru.mgp.MGPPlugin;
import ru.mgp.config.ModeConfig;
import ru.mgp.util.Items;

import java.util.ArrayList;
import java.util.List;

/** Меню выбора миниигры (компас в хабе, /play). */
public final class GameSelectorMenu extends Menu {
    public GameSelectorMenu(MGPPlugin plugin) {
        super(rowsFor(plugin.registry().modes().size()), "<dark_gray>Выбор игры");
        int slot = 0;
        for (ModeConfig m : plugin.registry().modes()) {
            List<String> lore = new ArrayList<>(m.description);
            lore.add("");
            lore.add("<gray>Играют: <yellow>" + plugin.sessions().playersIn(m.id));
            lore.add("<gray>Игроков в матче: <yellow>" + m.minPlayers + "-" + m.maxPlayers);
            lore.add("");
            lore.add("<green>▶ Нажми, чтобы играть");
            String id = m.id;
            set(slot++, Items.icon(m.icon, m.displayName, lore, false), p -> {
                p.closeInventory();
                plugin.sessions().join(p, id);
            });
        }
    }
}
