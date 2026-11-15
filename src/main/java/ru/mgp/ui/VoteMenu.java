package ru.mgp.ui;

import org.bukkit.entity.Player;
import ru.mgp.session.GameSession;
import ru.mgp.util.Items;
import ru.mgp.util.Msg;
import ru.mgp.vote.Vote;
import ru.mgp.vote.VoteType;

import java.util.ArrayList;
import java.util.List;

/** Меню голосований в лобби ожидания: список категорий → варианты. */
public final class VoteMenu extends Menu {

    public static void openFor(Player p, GameSession s) {
        List<Vote> list = interactive(s);
        if (list.isEmpty()) { Msg.send(p, "<gray>Голосований нет."); return; }
        if (list.size() == 1) new OptionsMenu(s, list.get(0), p, false).open(p);
        else new VoteMenu(s, p).open(p);
    }

    private static List<Vote> interactive(GameSession s) {
        List<Vote> out = new ArrayList<>();
        for (Vote v : s.votes().values()) if (v.interactive()) out.add(v);
        return out;
    }

    private VoteMenu(GameSession s, Player viewer) {
        super(rowsFor(interactive(s).size()), "<dark_gray>Голосование");
        int slot = 0;
        for (Vote v : interactive(s)) {
            String mine = v.votedFor(viewer.getUniqueId());
            Vote.Option o = mine == null ? null : v.option(mine);
            List<String> lore = new ArrayList<>();
            lore.add(v.closed() ? "<red>Голосование завершено" : "<gray>Твой выбор: " + (o != null ? o.display() : "<dark_gray>нет"));
            lore.add("");
            lore.add("<yellow>Нажми, чтобы выбрать");
            set(slot++, Items.icon(v.icon, v.title, lore, false), p -> new OptionsMenu(s, v, p, true).open(p));
        }
    }

    /** Варианты одного голосования. */
    static final class OptionsMenu extends Menu {
        OptionsMenu(GameSession s, Vote v, Player viewer, boolean back) {
            super(rowsFor(v.options.size() + (back ? 1 : 0)), "<dark_gray>" + Msg.esc(plain(v.title)));
            String mine = v.votedFor(viewer.getUniqueId());
            int slot = 0;
            for (Vote.Option o : v.options) {
                List<String> lore = new ArrayList<>(o.lore());
                lore.add("");
                lore.add("<gray>Голосов: <yellow>" + v.count(o.id()));
                if (v.type == VoteType.WEIGHTED) lore.add(String.format("<gray>Шанс: <yellow>%.0f%%", v.chance(o.id())));
                lore.add(o.id().equals(mine) ? "<green>✔ Твой голос" : "<yellow>Нажми, чтобы проголосовать");
                set(slot++, Items.icon(o.icon(), o.display(), lore, o.id().equals(mine)), p -> {
                    if (v.closed()) { Msg.send(p, "<red>Голосование уже завершено."); p.closeInventory(); return; }
                    v.cast(p.getUniqueId(), o.id());
                    Msg.send(p, "<green>Ты проголосовал за </green>" + o.display());
                    new OptionsMenu(s, v, p, back).open(p);
                });
            }
            if (back) {
                int last = getInventory().getSize() - 1;
                set(last, Items.icon(org.bukkit.Material.ARROW, "<gray>Назад", List.of(), false), p -> VoteMenu.openFor(p, s));
            }
        }

        private static String plain(String mm) {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(Msg.parse(mm));
        }
    }
}
