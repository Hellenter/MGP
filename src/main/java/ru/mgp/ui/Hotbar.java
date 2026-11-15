package ru.mgp.ui;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import ru.mgp.MGPPlugin;
import ru.mgp.network.LinkedServers;
import ru.mgp.session.GameSession;
import ru.mgp.vote.Vote;

/** Служебные предметы в хотбаре. Нажатия обрабатывает PlayerListener по метке предмета. */
public final class Hotbar {
    public static final String SELECTOR = "selector", VOTE = "vote", KIT = "kit", LEAVE = "leave", AGAIN = "again";

    private Hotbar() {}

    public static void giveHub(Player p) {
        PlayerInventory inv = p.getInventory();
        inv.setItem(0, tagged(Material.COMPASS, "<green><bold>Выбор игры</bold> <gray>(ПКМ)", SELECTOR));
        for (LinkedServers.Server s : MGPPlugin.getPlugin(MGPPlugin.class).servers().all()) {
            if (s.hotbarSlot() < 1 || s.hotbarSlot() > 8) continue;
            inv.setItem(s.hotbarSlot(), ru.mgp.util.Items.tagged(s.icon(), s.name() + " <gray>(ПКМ)", LinkedServers.TAG_PREFIX + s.id(),
                    s.description().toArray(String[]::new)));
        }
        inv.setHeldItemSlot(0);
    }

    public static void giveWaiting(Player p, GameSession s) {
        PlayerInventory inv = p.getInventory();
        boolean hasVotes = s.votes().values().stream().anyMatch(Vote::interactive);
        if (hasVotes) inv.setItem(0, tagged(Material.PAPER, "<gold><bold>Голосование</bold> <gray>(ПКМ)", VOTE));
        if (s.mode.kits.size() > 1) inv.setItem(1, tagged(Material.CHEST, "<aqua><bold>Наборы</bold> <gray>(ПКМ)", KIT));
        inv.setItem(8, tagged(Material.RED_BED, "<red><bold>Выйти в лобби</bold> <gray>(ПКМ)", LEAVE));
    }

    public static void giveSpectator(Player p) {
        PlayerInventory inv = p.getInventory();
        inv.setItem(0, tagged(Material.PAPER, "<green><bold>Играть ещё</bold> <gray>(ПКМ)", AGAIN));
        inv.setItem(8, tagged(Material.RED_BED, "<red><bold>Выйти в лобби</bold> <gray>(ПКМ)", LEAVE));
    }

    private static org.bukkit.inventory.ItemStack tagged(Material m, String name, String tag) {
        return ru.mgp.util.Items.tagged(m, name, tag);
    }
}
