package ru.mgp.npc;

import org.bukkit.entity.Player;
import ru.mgp.MGPPlugin;
import ru.mgp.config.MapConfig;
import ru.mgp.util.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /mgp npc — NPC:
 *   create <id> [карта] — поставить на своё место (с картой — NPC этой карты, работает в играх на ней);
 *   edit <id> — меню настройки (то же, что Shift+ПКМ по NPC); list; tp <id>; delete <id>.
 */
public final class NpcCommands {
    private NpcCommands() {}

    private static final List<String> SUBS = List.of("create", "edit", "list", "tp", "delete");

    public static void handle(MGPPlugin plugin, Player p, String[] a) {
        NpcService svc = plugin.npcs();
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "help";
        switch (sub) {
            case "create" -> {
                if (a.length < 3) { help(p); return; }
                String id = a[2].toLowerCase(Locale.ROOT);
                if (!id.matches("[a-z0-9_-]{1,32}")) { Msg.send(p, "<red>id NPC: латиница, цифры, _ и -"); return; }
                if (svc.get(id) != null) { Msg.send(p, "<red>NPC " + id + " уже есть. <gray>/mgp npc edit " + id); return; }
                String map = null;
                if (a.length > 3) {
                    MapConfig m = plugin.registry().map(a[3]);
                    if (m == null) { Msg.send(p, "<red>Нет карты " + Msg.esc(a[3])); return; }
                    map = m.id;
                }
                if (plugin.sessions().byWorld(p.getWorld()) != null) { Msg.send(p, "<red>Ставь NPC не в мире игры: в хабе или в мире, откуда взята карта."); return; }
                NpcService.Npc n = svc.create(id, map, p.getLocation());
                Msg.send(p, "<green>NPC " + id + " поставлен" + (map != null ? " для карты " + map + " <gray>(в этом мире его видят только админы, в играх — все)" : "")
                        + ". <yellow>Shift+ПКМ</yellow> по нему — настроить вид, скин, подпись и действия.");
                new NpcMenu(svc, n).open(p);
            }
            case "edit" -> {
                NpcService.Npc n = a.length > 2 ? svc.get(a[2]) : null;
                if (n == null) { Msg.send(p, "<red>Нет такого NPC. <gray>/mgp npc list"); return; }
                new NpcMenu(svc, n).open(p);
            }
            case "list" -> {
                if (svc.all().isEmpty()) { Msg.send(p, "<gray>NPC пока нет. Встань на место и: /mgp npc create <id> [карта]"); return; }
                Msg.send(p, "<gold>NPC (" + svc.all().size() + "):");
                for (NpcService.Npc n : svc.all()) {
                    p.sendMessage(Msg.parse("<gray>• <white>" + n.id + "</white> " + (n.map != null ? "<aqua>карта " + Msg.esc(n.map) + "</aqua>" : "<gray>" + Msg.esc(n.world))
                            + " <dark_gray>" + n.type + ", действий: " + n.actions.size() + "</dark_gray> "
                            + "<aqua><click:run_command:'/mgp npc tp " + n.id + "'><hover:show_text:'Телепорт'>[тп]</hover></click></aqua> "
                            + "<yellow><click:run_command:'/mgp npc edit " + n.id + "'><hover:show_text:'Настроить'>[настроить]</hover></click></yellow>"));
                }
            }
            case "tp" -> {
                NpcService.Npc n = a.length > 2 ? svc.get(a[2]) : null;
                if (n == null) { Msg.send(p, "<red>Нет такого NPC"); return; }
                var w = org.bukkit.Bukkit.getWorld(n.world);
                if (w == null) { Msg.send(p, "<red>Мир " + Msg.esc(n.world) + " не загружен"); return; }
                p.teleport(n.pos.toLocation(w));
            }
            case "delete", "remove" -> {
                NpcService.Npc n = a.length > 2 ? svc.get(a[2]) : null;
                if (n == null) { Msg.send(p, "<red>Нет такого NPC"); return; }
                svc.delete(n);
                Msg.send(p, "<green>NPC " + n.id + " удалён.");
            }
            default -> help(p);
        }
    }

    private static void help(Player p) {
        Msg.send(p, "<gold>/mgp npc create <id> [карта]</gold> — NPC на твоём месте. Без карты — в этом мире (хаб); с картой — появляется в играх на ней");
        Msg.send(p, "<gray>   Настройка — <yellow>Shift+ПКМ</yellow> по NPC: вид, скин игрока, имя и подпись ({players:режим}), действия: в режим, меню игр, сервер, свои");
        Msg.send(p, "<gold>/mgp npc list</gold>, <gold>edit <id></gold>, <gold>tp <id></gold>, <gold>delete <id></gold>");
    }

    public static List<String> complete(MGPPlugin plugin, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 2) out.addAll(SUBS);
        else if (a.length == 3 && !a[1].equalsIgnoreCase("create") && !a[1].equalsIgnoreCase("list"))
            plugin.npcs().all().forEach(n -> out.add(n.id));
        else if (a.length == 4 && a[1].equalsIgnoreCase("create"))
            plugin.registry().maps().forEach(m -> out.add(m.id));
        return out;
    }
}
