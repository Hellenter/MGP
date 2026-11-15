package ru.mgp.ui;

import org.bukkit.entity.Player;
import ru.mgp.config.Kit;
import ru.mgp.session.GameSession;
import ru.mgp.util.Items;

import java.util.ArrayList;
import java.util.List;

/** Выбор набора в лобби ожидания. */
public final class KitMenu extends Menu {
    public KitMenu(GameSession s, Player viewer) {
        super(rowsFor(s.mode.kits.size()), "<dark_gray>Наборы");
        String current = s.kitOf(viewer.getUniqueId());
        int slot = 0;
        for (Kit k : s.mode.kits.values()) {
            boolean allowed = k.permission() == null || viewer.hasPermission(k.permission());
            List<String> lore = new ArrayList<>(k.description());
            lore.add("");
            if (k.id().equals(current)) lore.add("<green>✔ Выбран");
            else lore.add(allowed ? "<yellow>Нажми, чтобы выбрать" : "<red>Недоступно");
            set(slot++, Items.icon(k.icon(), k.display(), lore, k.id().equals(current)), p -> {
                s.selectKit(p, k.id());
                new KitMenu(s, p).open(p);
            });
        }
    }
}
