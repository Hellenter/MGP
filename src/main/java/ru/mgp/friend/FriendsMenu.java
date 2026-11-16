package ru.mgp.friend;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import ru.mgp.MGPPlugin;
import ru.mgp.party.Party;
import ru.mgp.party.PartyMenu;
import ru.mgp.ui.Menu;
import ru.mgp.util.Items;
import ru.mgp.util.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** /f — друзья головами: ЛКМ — пригласить в пати, Shift+ПКМ — удалить; ниже входящие запросы. */
public final class FriendsMenu extends Menu {

    public FriendsMenu(MGPPlugin plugin, Player viewer) {
        super(6, "<dark_gray>Друзья");
        FriendService fs = plugin.friends();
        UUID me = viewer.getUniqueId();
        Party party = plugin.parties().of(viewer);
        boolean canInvite = party == null || party.isLeader(me);

        List<UUID> list = fs.sorted(me);
        int slot = 0;
        for (UUID f : list) {
            if (slot >= 36) break;
            Player o = Bukkit.getPlayer(f);
            String n = FriendService.name(f);
            boolean inParty = party != null && party.members().contains(f);
            List<String> lore = new ArrayList<>();
            lore.add(o == null ? "<dark_gray>Не в сети" : fs.status(o));
            lore.add("");
            if (o != null && !inParty) lore.add(canInvite ? "<yellow>ЛКМ <gray>— пригласить в пати" : "<gray>Приглашать может лидер пати");
            if (inParty) lore.add("<aqua>Уже в твоей пати");
            lore.add("<red>Shift+ПКМ <gray>— удалить из друзей");
            setClick(slot++, head(f, (o == null ? "<gray>" : "<green>") + Msg.esc(n), lore), (p, c) -> {
                if (c == ClickType.SHIFT_RIGHT) { fs.remove(p, f); new FriendsMenu(plugin, p).open(p); return; }
                if (!c.isLeftClick() || inParty) return;
                Player t = Bukkit.getPlayer(f);
                if (t == null) { p.sendActionBar(Msg.parse("<gray>" + Msg.esc(n) + " не в сети")); return; }
                p.closeInventory();
                plugin.parties().invite(p, t);
            });
        }
        if (list.isEmpty()) set(13, Items.icon(Material.PLAYER_HEAD, "<white>Пока нет друзей", List.of(
                "<gray>Добавь игрока: <yellow>/f add <ник>",
                "<gray>или ПКМ по его голове в меню пати."), false), null);

        // входящие запросы
        slot = 36;
        for (UUID f : fs.incoming(me)) {
            if (slot >= 45) break;
            String n = FriendService.name(f);
            setClick(slot++, head(f, "<yellow>" + Msg.esc(n), List.of("<gray>Хочет добавить тебя в друзья", "",
                    "<green>ЛКМ <gray>— принять", "<red>ПКМ <gray>— отклонить")), (p, c) -> {
                if (c.isLeftClick()) fs.accept(p, n); else if (c.isRightClick()) fs.deny(p, n);
                new FriendsMenu(plugin, p).open(p);
            });
        }

        set(45, Items.icon(Material.BOOK, "<aqua>Как это работает", List.of(
                "<yellow>/f add <ник> <gray>— запрос в друзья",
                "<yellow>/f accept <gray>/ <yellow>deny <gray>— ответить",
                "<yellow>/f remove <ник> <gray>— удалить",
                "<yellow>/f list <gray>— список в чат",
                "",
                "<gray>Друзья видят, когда ты заходишь,",
                "<gray>и стоят первыми в меню пати."), false), null);
        set(47, Items.icon(Material.WRITABLE_BOOK, "<green>Добавить друга", List.of("<gray>Введи ник в чат"), false), p ->
                plugin.wand().ask(p, "<aqua>Ник игрока, которого добавить в друзья", text -> {
                    Player t = Bukkit.getPlayerExact(text.trim());
                    if (t == null) { FriendService.msg(p, "<red>Игрок " + Msg.esc(text) + " не в сети."); return; }
                    fs.add(p, t);
                }));
        set(49, Items.icon(Material.ARROW, "<gray>Меню пати", List.of(), false), p -> new PartyMenu(plugin, p).open(p));
        long on = list.stream().filter(u -> Bukkit.getPlayer(u) != null).count();
        set(53, Items.icon(Material.EMERALD, "<green>Друзей: <white>" + list.size(), List.of("<gray>В сети: <green>" + on), false), null);
    }

    /** Голова игрока (онлайн или нет). */
    public static ItemStack head(UUID u, String name, List<String> lore) {
        ItemStack it = Items.icon(Material.PLAYER_HEAD, name, lore, false);
        if (it.getItemMeta() instanceof SkullMeta sm) {
            Player o = Bukkit.getPlayer(u);
            if (o != null) sm.setPlayerProfile(o.getPlayerProfile());
            else sm.setOwningPlayer(Bukkit.getOfflinePlayer(u));
            it.setItemMeta(sm);
        }
        return it;
    }
}
