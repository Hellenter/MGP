package ru.mgp.party;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import ru.mgp.MGPPlugin;
import ru.mgp.session.GameSession;
import ru.mgp.ui.Menu;
import ru.mgp.util.Items;
import ru.mgp.util.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Меню пати (предмет «Пати» в хабе или /p): состав, действия лидера и список игроков онлайн для приглашения. */
public final class PartyMenu extends Menu {

    public PartyMenu(MGPPlugin plugin, Player viewer) {
        super(6, "<dark_gray>Пати");
        PartyService ps = plugin.parties();
        Party party = ps.of(viewer);
        boolean leader = party == null || party.isLeader(viewer.getUniqueId());

        // ряд 1: участники
        int slot = 0;
        if (party != null) {
            for (UUID u : party.members()) {
                if (slot > 8) break;
                Player m = Bukkit.getPlayer(u);
                List<String> lore = new ArrayList<>();
                lore.add(party.isLeader(u) ? "<gold>★ Лидер пати" : "<gray>Участник");
                lore.add(m == null ? "<red>Не в сети" : status(plugin, m));
                if (leader && !u.equals(viewer.getUniqueId())) {
                    lore.add("");
                    lore.add("<yellow>ЛКМ <gray>— сделать лидером");
                }
                String n = PartyService.name(u);
                set(slot++, head(m, (party.isLeader(u) ? "<gold>" : "<white>") + Msg.esc(n), lore),
                        leader && !u.equals(viewer.getUniqueId()) ? p -> { ps.transfer(p, n); p.closeInventory(); } : null);
            }
        } else {
            set(4, Items.icon(Material.PLAYER_HEAD, "<white>Ты пока не в пати", List.of("<gray>Пригласи игроков снизу —", "<gray>ты станешь лидером."), false), null);
        }

        // ряд 2: действия
        set(9, Items.icon(Material.BOOK, "<aqua>Как это работает", List.of(
                "<gray>Игры запускает лидер: когда он",
                "<gray>заходит в режим, вся пати попадает",
                "<gray>в одну арену.",
                "",
                "<yellow>/p <ник> <gray>— пригласить",
                "<yellow>/pc <текст> <gray>— чат пати",
                "<gray>ПКМ этим предметом по игроку —",
                "<gray>тоже приглашение."), false), null);
        if (party != null) {
            set(11, Items.icon(Material.PAPER, "<white>Состав пати", List.of("<gray>Написать в чат состав и приглашения"), false), p -> { p.closeInventory(); ps.list(p); });
            if (leader) {
                set(13, Items.icon(Material.ENDER_PEARL, "<light_purple>Собрать пати", List.of("<gray>/p warp: в твоё лобби ожидания", "<gray>или в хаб"), false), p -> { p.closeInventory(); ps.warp(p); });
                set(15, Items.icon(Material.TNT, "<red>Распустить пати", List.of("<gray>/p disband"), false), p -> { p.closeInventory(); ps.disband(p); });
            }
            set(17, Items.icon(Material.BARRIER, "<red>Покинуть пати", List.of("<gray>/p leave"), false), p -> { p.closeInventory(); ps.leave(p); });
        }

        // ряды 3-6: кого пригласить
        slot = 18;
        if (leader) {
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (slot >= 54) break;
                if (o.equals(viewer) || (party != null && party.members().contains(o.getUniqueId()))) continue;
                boolean invited = party != null && party.invites.containsKey(o.getUniqueId());
                List<String> lore = new ArrayList<>();
                lore.add(status(plugin, o));
                lore.add("");
                lore.add(invited ? "<gray>Приглашение отправлено" : "<green>Нажми — пригласить в пати");
                set(slot++, head(o, "<white>" + Msg.esc(o.getName()), lore), invited ? null : p -> { p.closeInventory(); ps.invite(p, o); });
            }
            if (slot == 18) set(31, Items.icon(Material.GRAY_DYE, "<gray>Больше никого нет в сети", List.of(), false), null);
        } else {
            set(31, Items.icon(Material.GRAY_DYE, "<gray>Приглашать может только лидер", List.of(), false), null);
        }
    }

    private static String status(MGPPlugin plugin, Player p) {
        GameSession s = plugin.sessions().of(p);
        if (s == null) return "<green>В хабе";
        return (s.isPlaying() ? "<gold>Играет: " : "<yellow>Ждёт игру: ") + s.mode.displayName;
    }

    private static ItemStack head(Player p, String name, List<String> lore) {
        ItemStack it = Items.icon(Material.PLAYER_HEAD, name, lore, false);
        if (p != null && it.getItemMeta() instanceof SkullMeta sm) {
            sm.setPlayerProfile(p.getPlayerProfile());
            it.setItemMeta(sm);
        }
        return it;
    }
}
