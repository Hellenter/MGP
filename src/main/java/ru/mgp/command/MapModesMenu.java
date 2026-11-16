package ru.mgp.command;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ru.mgp.MGPPlugin;
import ru.mgp.config.MapConfig;
import ru.mgp.config.ModeConfig;
import ru.mgp.ui.Menu;
import ru.mgp.util.Items;
import ru.mgp.util.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /mgp setup modes <карта> — в каких режимах играется карта: клик включает/выключает режим, Shift+клик — основной. */
final class MapModesMenu extends Menu {

    MapModesMenu(MGPPlugin plugin, MapConfig map) {
        super(rowsFor(plugin.registry().modes().size()), "<dark_gray>Режимы карты " + map.id);
        int slot = 0;
        for (ModeConfig mode : plugin.registry().modes()) {
            boolean main = mode.id.equalsIgnoreCase(map.mode);
            boolean on = map.playableIn(mode.id);
            List<String> lore = new ArrayList<>();
            lore.add(main ? "<gold>★ Основной режим карты" : on ? "<green>✔ Карта играется в этом режиме" : "<gray>Карта в этом режиме не играется");
            lore.add("<gray>Карт в режиме: <white>" + plugin.registry().mapsFor(mode.id).size());
            lore.add("");
            if (!main) lore.add(on ? "<red>Клик — убрать" : "<green>Клик — добавить");
            if (!main) lore.add("<yellow>Shift+клик — сделать основным");
            String id = mode.id;
            setClick(slot++, Items.icon(mode.icon, mode.displayName + " <dark_gray>(" + id + ")", lore, on), (p, c) -> {
                if (main) return;
                List<String> modes = new ArrayList<>();
                modes.add(map.mode);
                modes.addAll(map.alsoModes);
                if (c.isShiftClick()) { modes.remove(id); modes.add(0, id); }
                else if (on) modes.remove(id);
                else modes.add(id);
                apply(plugin, p, map, modes);
                MapConfig upd = plugin.registry().map(map.id);
                if (upd != null) new MapModesMenu(plugin, upd).open(p);
            });
        }
    }

    /** Записать режимы карты: первый — основной (mode), остальные — also-modes. */
    static void apply(MGPPlugin plugin, Player p, MapConfig map, List<String> modes) {
        List<String> clean = new ArrayList<>();
        for (String m : modes) {
            String id = m.toLowerCase(Locale.ROOT);
            if (!id.isBlank() && !clean.contains(id)) clean.add(id);
        }
        if (clean.isEmpty()) { Msg.send(p, "<red>У карты должен остаться хотя бы один режим"); return; }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(map.file);
        y.set("mode", clean.get(0));
        y.set("also-modes", clean.size() > 1 ? clean.subList(1, clean.size()) : null);
        try {
            y.save(map.file);
        } catch (java.io.IOException e) {
            Msg.send(p, "<red>Не удалось сохранить: " + Msg.esc(e.getMessage()));
            return;
        }
        plugin.registry().loadMap(map.file);
        List<String> names = new ArrayList<>();
        for (String id : clean) {
            ModeConfig mc = plugin.registry().mode(id);
            names.add(mc == null ? "<red>" + id + "?" : mc.displayName);
        }
        Msg.send(p, "<green>Карта " + map.id + " играется в: " + String.join("<gray>, ", names));
    }
}
