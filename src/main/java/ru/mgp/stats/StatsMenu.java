package ru.mgp.stats;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import ru.mgp.MGPPlugin;
import ru.mgp.config.ModeConfig;
import ru.mgp.ui.Menu;
import ru.mgp.util.Items;
import ru.mgp.util.Msg;

import java.util.ArrayList;
import java.util.List;

/** /stats: голова игрока с общей статистикой и по иконке на каждый режим. */
public final class StatsMenu extends Menu {
    public StatsMenu(MGPPlugin plugin, StatsService.PlayerStats ps, String name) {
        super(2 + rowsFor(Math.max(1, plugin.stats().modeIds().size())), "<dark_gray>Статистика: " + Msg.esc(name));
        StatsService stats = plugin.stats();
        Counts total = ps == null ? new Counts() : ps.counts(StatsService.ALL);

        List<String> lore = lines(total);
        if (ps != null) {
            lore.add("");
            int rw = stats.rank(ps.uuid, Stat.WINS, StatsService.ALL);
            int rk = stats.rank(ps.uuid, Stat.KILLS, StatsService.ALL);
            lore.add("<gray>Место по победам: " + (rw > 0 ? "<gold>#" + rw : "<dark_gray>—"));
            lore.add("<gray>Место по убийствам: " + (rk > 0 ? "<gold>#" + rk : "<dark_gray>—"));
        }
        ItemStack head = Items.icon(Material.PLAYER_HEAD, "<yellow><bold>" + Msg.esc(name) + "</bold> <gray>— всего", lore, false);
        if (ps != null && head.getItemMeta() instanceof SkullMeta sm) {
            sm.setOwningPlayer(Bukkit.getOfflinePlayer(ps.uuid));
            head.setItemMeta(sm);
        }
        set(4, head, null);

        int slot = 18;
        for (String id : stats.modeIds()) {
            ModeConfig m = plugin.registry().mode(id);
            Counts c = ps == null ? new Counts() : ps.counts(id);
            if (m == null && c.empty()) continue; // удалённый режим без статистики
            Material icon = m != null ? m.icon : Material.PAPER;
            List<String> l = c.empty() ? new ArrayList<>(List.of("<dark_gray>Ещё не играл")) : lines(c);
            set(slot++, Items.icon(icon, "<green>" + stats.modeName(id), l, false), null);
        }
    }

    private static List<String> lines(Counts c) {
        List<String> l = new ArrayList<>();
        l.add("<gray>Сыграно: <white>" + c.played);
        l.add("<gray>Победы: <green>" + c.wins + " <dark_gray>(" + c.winRate() + "%)");
        l.add("<gray>Убийства: <red>" + c.kills);
        l.add("<gray>Смерти: <white>" + c.deaths);
        l.add("<gray>K/D: <yellow>" + Stat.KD.format(c.kd()));
        return l;
    }
}
