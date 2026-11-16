package ru.mgp.ui;

import org.bukkit.entity.Player;
import ru.mgp.config.Kit;
import ru.mgp.session.GameSession;
import ru.mgp.util.Items;

import java.util.ArrayList;
import java.util.List;

/** Выбор набора в лобби ожидания. */
public final class KitMenu extends Menu {
    private static String teamName(String t) {
        return switch (t.toLowerCase(java.util.Locale.ROOT)) {
            case "defenders" -> "защитников";
            case "attackers" -> "атакующих";
            case "seeker", "seekers" -> "ищущих";
            case "hider", "hiders" -> "прячущихся";
            default -> t;
        };
    }

    public KitMenu(GameSession s, Player viewer) {
        super(rowsFor(s.mode.kits.size()), "<dark_gray>Наборы");
        String current = s.kitOf(viewer.getUniqueId());
        int slot = 0;
        for (Kit k : s.mode.kits.values()) {
            boolean allowed = k.permission() == null || viewer.hasPermission(k.permission());
            List<String> lore = new ArrayList<>(k.description());
            if (k.team() != null) lore.add("<gray>Только для команды <white>" + teamName(k.team()));
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
