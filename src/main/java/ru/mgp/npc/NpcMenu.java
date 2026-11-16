package ru.mgp.npc;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import ru.mgp.config.ModeConfig;
import ru.mgp.network.LinkedServers;
import ru.mgp.ui.Menu;
import ru.mgp.util.Items;
import ru.mgp.util.Msg;
import ru.mgp.util.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Настройка NPC: вид, скин, подпись, действия по клику. Открывается Shift+ПКМ по NPC или /mgp npc edit <id>. */
final class NpcMenu extends Menu {
    private final NpcService svc;
    private final NpcService.Npc n;

    NpcMenu(NpcService svc, NpcService.Npc n) {
        super(5, "<dark_gray>NPC " + n.id);
        this.svc = svc;
        this.n = n;

        set(4, Items.icon(Material.PLAYER_HEAD, "<gold><bold>NPC " + n.id, List.of(
                n.map != null ? "<gray>NPC карты <white>" + Msg.esc(n.map) + "</white>: появляется в играх на ней" : "<gray>NPC мира <white>" + Msg.esc(n.world),
                "<gray>Точка: <white>" + n.pos.serialize()), false), null);

        int ti = Math.max(0, NpcService.TYPES.indexOf(n.type));
        setClick(10, Items.icon(egg(n.type), "<aqua><bold>Вид: <white>" + n.type, List.of(
                "<gray>ЛКМ — следующий, ПКМ — предыдущий", "<gray>Shift — ввести любой (ZOMBIE, HUSK...)"), false), (p, c) -> {
            if (c.isShiftClick()) {
                svc.ask(p, "<aqua>Вид сущности (английское имя, например <white>VILLAGER</white>, <white>MANNEQUIN</white>):", t -> {
                    String v = t.trim().toUpperCase(Locale.ROOT);
                    try {
                        var et = org.bukkit.entity.EntityType.valueOf(v);
                        if (et.getEntityClass() == null || !org.bukkit.entity.LivingEntity.class.isAssignableFrom(et.getEntityClass()) || !et.isSpawnable())
                            throw new IllegalArgumentException();
                    } catch (IllegalArgumentException e) {
                        Msg.send(p, "<red>Такой живой сущности нет: " + Msg.esc(t));
                        reopen(p);
                        return;
                    }
                    n.type = v;
                    done(p);
                });
                return;
            }
            int size = NpcService.TYPES.size();
            n.type = NpcService.TYPES.get(Math.floorMod(ti + (c.isRightClick() ? -1 : 1), size));
            done(p);
        });

        setClick(11, Items.icon(Material.PLAYER_HEAD, "<aqua><bold>Скин: <white>" + (n.skin == null ? "по умолчанию" : Msg.esc(n.skin)), List.of(
                "<gray>Только для вида MANNEQUIN (человек)", "<gray>ЛКМ — ник игрока, чей скин взять", "<gray>ПКМ — мой скин"), false), (p, c) -> {
            if (c.isRightClick()) {
                n.skin = p.getName();
                n.type = "MANNEQUIN";
                done(p);
                return;
            }
            svc.ask(p, "<aqua>Ник игрока (скин берётся с серверов Mojang):", t -> {
                if (!t.matches("[A-Za-z0-9_]{1,16}")) { Msg.send(p, "<red>Ник: латиница, цифры и _, до 16 символов"); reopen(p); return; }
                n.skin = t;
                n.type = "MANNEQUIN";
                done(p);
            });
        });

        setClick(12, Items.icon(Material.NAME_TAG, "<aqua><bold>Имя", List.of("<gray>Сейчас: <reset>" + (n.name == null ? "<gray>нет" : n.name),
                "<gray>ЛКМ — изменить, ПКМ — убрать"), false), (p, c) -> {
            if (c.isRightClick()) { n.name = null; done(p); return; }
            svc.ask(p, "<aqua>Имя над головой (цвета: <gold>... , <bold>...):", t -> { n.name = t; done(p); });
        });

        List<String> lineLore = new ArrayList<>();
        for (String l : n.lines) lineLore.add("<white>• <reset>" + l);
        if (lineLore.isEmpty()) lineLore.add("<gray>нет строк");
        lineLore.add("");
        lineLore.add("<gray>ЛКМ — добавить строку, ПКМ — убрать последнюю");
        lineLore.add("<gray>{players:<режим>} — сколько играют, {online} — онлайн");
        setClick(13, Items.icon(Material.OAK_SIGN, "<aqua><bold>Подпись под именем", lineLore, false), (p, c) -> {
            if (c.isRightClick()) {
                if (!n.lines.isEmpty()) n.lines.remove(n.lines.size() - 1);
                done(p);
                return;
            }
            svc.ask(p, "<aqua>Строка подписи. Например: <white><gray>Играют: <yellow>{players:hunger_games}", t -> { n.lines.add(t); done(p); });
        });

        set(14, Items.icon(n.look ? Material.ENDER_EYE : Material.ENDER_PEARL, "<aqua><bold>Смотрит на игроков: " + (n.look ? "<green>да" : "<red>нет"),
                List.of("<gray>Поворачивает голову к ближайшему игроку"), false), p -> { n.look = !n.look; done(p); });

        set(15, Items.icon(Material.COMPASS, "<aqua><bold>Переставить сюда", List.of("<gray>NPC встанет на твоё место", "<gray>и будет смотреть, куда смотришь ты"), false), p -> {
            if (!p.getWorld().getName().equals(n.world) && n.map == null) n.world = p.getWorld().getName();
            n.pos = Pos.of(p.getLocation());
            done(p);
        });

        setClick(16, Items.icon(Material.BARRIER, "<red><bold>Удалить NPC", List.of("<gray>Shift+клик"), false), (p, c) -> {
            if (!c.isShiftClick()) return;
            svc.delete(n);
            p.closeInventory();
            Msg.send(p, "<green>NPC " + n.id + " удалён.");
        });

        // ---- действия
        List<String> actLore = new ArrayList<>();
        for (String a : n.actions) actLore.add("<white>• " + Msg.esc(a));
        if (actLore.isEmpty()) actLore.add("<gray>нет — клик ничего не делает");
        actLore.add("");
        actLore.add("<gray>ПКМ — убрать последнее, Shift — убрать все");
        setClick(27, Items.icon(Material.WRITABLE_BOOK, "<gold><bold>Действия по клику", actLore, false), (p, c) -> {
            if (c.isShiftClick()) n.actions.clear();
            else if (c.isRightClick() && !n.actions.isEmpty()) n.actions.remove(n.actions.size() - 1);
            else return;
            done(p);
        });
        set(29, Items.icon(Material.DIAMOND_SWORD, "<green><bold>В режим", List.of("<gray>Клик по NPC ставит в очередь режима", "<gray>(с пати, как через меню игр)"), false),
                p -> new ModePick(this, p).open(p));
        set(30, Items.icon(Material.COMPASS, "<green><bold>Открыть меню игр", List.of(), false), p -> add(p, "menu"));
        set(31, Items.icon(Material.ENDER_PEARL, "<green><bold>На другой сервер", List.of("<gray>Серверы сети из config.yml (например smp)"), false), p -> {
            List<LinkedServers.Server> list = svc.plugin().servers().all();
            if (list.isEmpty()) { Msg.send(p, "<red>Серверов сети нет в config.yml"); return; }
            svc.ask(p, "<aqua>id сервера: <white>" + String.join(", ", list.stream().map(LinkedServers.Server::id).toList()), t -> add(p, "server: " + t.trim()));
        });
        set(32, Items.icon(Material.PAPER, "<green><bold>Сообщение", List.of("<gray>Только этому игроку"), false),
                p -> svc.ask(p, "<aqua>Текст сообщения:", t -> add(p, "message: " + t)));
        set(33, Items.icon(Material.OAK_DOOR, "<green><bold>Выйти в хаб", List.of("<gray>Для NPC на картах: покинуть игру"), false), p -> add(p, "leave"));
        set(34, Items.icon(Material.COMMAND_BLOCK, "<green><bold>Своё действие", List.of(
                "<gray>Любое действие скриптов, например:", "<white>title: <gold>Привет | <gray>подзаголовок", "<white>sound: minecraft:entity.player.levelup",
                "<white>player-command: spawn", "<white>command: give {player} diamond 1", "<white>wait: 2"), false),
                p -> svc.ask(p, "<aqua>Действие (тип: аргументы):", t -> add(p, t)));

        set(40, Items.icon(Material.LIME_DYE, "<green><bold>Готово", List.of(), false), Player::closeInventory);
    }

    private void add(Player p, String action) {
        n.actions.add(action);
        done(p);
    }

    private void done(Player p) {
        svc.changed(n);
        reopen(p);
    }

    private void reopen(Player p) { new NpcMenu(svc, n).open(p); }

    private static Material egg(String type) {
        if (type.equals("MANNEQUIN")) return Material.PLAYER_HEAD;
        if (type.equals("ARMOR_STAND")) return Material.ARMOR_STAND;
        Material m = Material.matchMaterial(type + "_SPAWN_EGG");
        return m != null ? m : Material.EGG;
    }

    /** Выбор режима для действия join. */
    private static final class ModePick extends Menu {
        ModePick(NpcMenu back, Player viewer) {
            super(rowsFor(back.svc.plugin().registry().modes().size() + 1), "<dark_gray>В какой режим");
            int slot = 0;
            for (ModeConfig m : back.svc.plugin().registry().modes()) {
                String id = m.id;
                set(slot++, Items.icon(m.icon, m.displayName, List.of("<gray>id: " + id), false), p -> back.add(p, "join: " + id));
            }
        }
    }
}
