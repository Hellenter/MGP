package ru.mgp.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.mgp.MGPPlugin;
import ru.mgp.config.MapConfig;
import ru.mgp.config.ModeConfig;
import ru.mgp.util.Msg;

import java.util.*;

/**
 * Справка /mgp help [раздел]: короткая главная страница и разделы. Каждая строка кликабельна — подставляет команду в чат.
 * Для текущей карты показываются команды её режима.
 */
final class Help {
    /** Строка справки: команда (что подставить), её вид в справке и описание. */
    private record Line(String insert, String shown, String desc) {}

    private static Line l(String shown, String desc) {
        // подставляем всё до первого аргумента в <> или [], чтобы оставалось дописать нужное
        int cut = shown.length();
        for (char c : new char[]{'<', '['}) { int i = shown.indexOf(c); if (i >= 0) cut = Math.min(cut, i); }
        String insert = shown.substring(0, cut);
        return new Line(insert.endsWith(" ") || cut == shown.length() ? insert : insert + " ", shown, desc);
    }

    static final Map<String, String> TITLES = new LinkedHashMap<>();
    static final Map<String, List<Line>> SECTIONS = new LinkedHashMap<>();

    private static void section(String id, String title, Line... lines) {
        TITLES.put(id, title);
        SECTIONS.put(id, List.of(lines));
    }

    static {
        section("maps", "Карты",
                l("/mgp import <карта> <режим>", "вырезать карту из мира по выделению (топор из /mgp tools)"),
                l("/mgp map [карта]", "текущая карта: с ней id карты в командах можно не писать (сама определяется, когда стоишь в карте)"),
                l("/mgp check [карта]", "чек-лист: что настроено и чего не хватает режиму, с кнопками исправления"),
                l("/mgp setup", "меню текущей карты: точки, зоны из выделения, режимы, название"),
                l("/mgp tools", "набор строителя: топор выделения, жезл точек, меню карты, палочка скриптов"),
                l("/mgp setup spawn", "спавн там, где стоишь; delspawn [номер] — удалить ближайший; spawns — список с [тп]"),
                l("/mgp setup spectator", "точка наблюдателей"),
                l("/mgp setup name <название>", "название для игроков (MiniMessage: <green>Джунгли)"),
                l("/mgp setup modes [режим...]", "в каких режимах играется карта (без аргументов — меню)"),
                l("/mgp setup show [сек]", "подсветить частицами все зоны и участки карты"),
                l("/mgp setup biome <биом|none>", "биом на всю карту"),
                l("/mgp maps", "карты по режимам (красные — не готовы)"),
                l("/mgp rename <новый id> [название]", "переименовать карту; /mgp optimize — обрезать пустые края"),
                l("/mgp delete <карта>", "удалить карту и её шаблон (спросит подтверждение)"),
                l("/mgp setup lobby <режим|default>", "лобби ожидания из выделения, спавн — где стоишь"));
        section("sel", "Выделение и зоны",
                l("/mgp setup wand", "золотой топор: ЛКМ по блоку — pos1, ПКМ — pos2 (или /mgp setup pos1 / pos2)"),
                l("/mgp setup sel", "меню граней выделения; expand <n> [vert], contract, shift, outset, inset, face, box"),
                l("/mgp setup clearsel", "убрать выделение"),
                l("/mgp setup zone [clear]", "play-зона из выделения: за неё не выйти"),
                l("/mgp setup teamzone <команда> [clear]", "стартовая зона команды; teamzone open <сек> — когда открывается"),
                l("/mgp setup trigger [id] [once|per_player|repeat]", "зона-триггер из выделения: вход / внутри / выход, действия — в меню"));
        section("hg", "Голодные игры",
                l("/mgp setup spawn", "спавны на пьедесталах (по одному на игрока)"),
                l("/mgp setup center [auto|размер]", "центр барьера; auto — по спавнам, размер — ширина барьера"),
                l("/mgp setup chest <тир>", "сундук, на который смотришь (обычно не нужно: сундуки находятся сами)"));
        section("bb", "Билд баттл",
                l("/mgp setup plot", "участок из выделения, спавн участка — где стоишь; plots — список"),
                l("/mgp setup wall [auto|clear]", "стены между участками: auto находит сам"),
                l("/mgp setup arena", "центр арены для голосования"));
        section("hns", "Прятки и Prop Hunt",
                l("/mgp setup spawn", "спавны прячущихся (или teamspawn hider)"),
                l("/mgp setup seekerspawn [none]", "комната ищущих, где они ждут, пока все прячутся"),
                l("/mgp setup teamspawn <команда> [группа] [locked]", "спавн команды: hider / seeker"));
        section("siege", "Осада",
                l("/mgp setup fortress [list|del <n>|clear]", "часть крепости из выделения"),
                l("/mgp setup teamspawn <команда>", "спавн defenders / attackers"),
                l("/mgp setup teamzone <команда>", "где команда сидит на подготовке; teamzone open <сек>"));
        section("assault", "Штурм",
                l("/mgp setup objective <id> [процент] [название]", "цель на разрушение из выделения"),
                l("/mgp setup assault stage <n> [время] [название]", "стадия; потом stage <n> objective <id>"),
                l("/mgp setup assault stage <n> spawn <команда>", "спавн стадии там, где стоишь"),
                l("/mgp setup assault stage <n> zone <команда>", "зона команды в эту стадию из выделения"),
                l("/mgp setup assault limbo <команда>", "лимбо команды (магазин после смерти)"),
                l("/mgp setup assault exit", "выход из лимбо из выделения"),
                l("/mgp setup assault info", "что уже настроено по стадиям"));
        section("scripts", "Скрипты и цели",
                l("/mgp wand", "палочка скриптов: ЛКМ по блоку — телепорты, кнопки, ловушки"),
                l("/mgp setup script <id> hold|button|plate|zone", "скрипт на блоке, на который смотришь"),
                l("/mgp setup script <id> action <действие>", "действие скрипта; tphere, set, clear, remove"),
                l("/mgp setup objective <id> action <действие>", "что случится, когда цель разрушена"),
                l("/mgp setup scripts", "скрипты карты; objectives — цели"));
        section("items", "Наборы, магазины, NPC, оружие",
                l("/mgp kit save <режим> <id> [команда]", "набор режима из своего инвентаря; kit list|load|delete"),
                l("/mgp shop [id]", "магазины в стиле Bed Wars (редактор в игре)"),
                l("/mgp npc create <id> [карта]", "NPC; настройка — Shift+ПКМ по нему; npc list|edit|tp|delete"),
                l("/mgp gun [menu|give|ammo|list]", "оружие и патроны; /mgp pack load|unload — ресурспак"),
                l("/mgp arrow [кол-во] [тип]", "разрывные стрелы"),
                l("/mgp setup trader <набор сделок> [профессия]", "житель-торговец на карте"));
        section("server", "Сервер",
                l("/mgp start", "запустить игру в лобби ожидания сейчас"),
                l("/mgp list", "идущие игры"),
                l("/mgp reload", "перечитать конфиги"),
                l("/mgp panel", "ссылка на админ-панель (в браузере)"),
                l("/mgp sethub", "спавн хаба там, где стоишь"),
                l("/mgp hotbar [on|off]", "предметы хаба у себя"),
                l("/mgp debug solo [on|off]", "играть одному для проверки; /mgp debug <режим> [карта] — что видит плагин"));
    }

    /** Раздел для режима карты. */
    private static String sectionForType(String type) {
        return switch (type) {
            case "hunger_games" -> "hg";
            case "build_battle" -> "bb";
            case "hide_and_seek" -> "hns";
            case "siege" -> "siege";
            case "assault" -> "assault";
            default -> null;
        };
    }

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("карты", "maps"), Map.entry("map", "maps"), Map.entry("выделение", "sel"), Map.entry("зоны", "sel"),
            Map.entry("selection", "sel"), Map.entry("hunger_games", "hg"), Map.entry("build_battle", "bb"), Map.entry("hide_and_seek", "hns"),
            Map.entry("prop_hunt", "hns"), Map.entry("осада", "siege"), Map.entry("штурм", "assault"), Map.entry("скрипты", "scripts"),
            Map.entry("предметы", "items"), Map.entry("kit", "items"), Map.entry("shop", "items"), Map.entry("сервер", "server"));

    static void show(MGPPlugin plugin, CommandSender s, String section) {
        boolean admin = s.hasPermission("mgp.admin");
        if (!admin) {
            Msg.send(s, "<yellow>/play [режим]</yellow> — меню игр или быстрый вход; <yellow>/leave</yellow> — выйти в лобби");
            Msg.send(s, "<yellow>/party</yellow>, <yellow>/friend</yellow> — пати и друзья");
            return;
        }
        String key = section == null ? null : section.toLowerCase(Locale.ROOT);
        if (key != null) {
            key = ALIASES.getOrDefault(key, key);
            ModeConfig mode = plugin.registry().mode(key);
            if (mode != null && sectionForType(mode.type) != null) key = sectionForType(mode.type);
        }
        if (key != null && SECTIONS.containsKey(key)) {
            Msg.send(s, "<gold>MGP — " + TITLES.get(key) + " <dark_gray>(клик по строке подставит команду)");
            for (Line line : SECTIONS.get(key)) s.sendMessage(Msg.parse(render(line)));
            s.sendMessage(Msg.parse(sectionBar()));
            return;
        }
        // главная страница
        Msg.send(s, "<gold>MGP — главное <dark_gray>(клик по строке подставит команду)");
        if (s instanceof Player p) {
            MapContext.Current c = plugin.mapContext().current(p);
            if (c != null) {
                MapConfig m = c.map();
                s.sendMessage(Msg.parse("<gray>Текущая карта: <yellow>" + m.id + "</yellow> <dark_gray>(" + c.how() + ")<gray>, готовность: "
                        + MapCheck.summary(MapCheck.run(plugin, m))));
                s.sendMessage(Msg.parse(MgpCommand.mapButtons(m.id)));
                Set<String> secs = new LinkedHashSet<>();
                for (String mid : MapCheck.modesOf(m)) {
                    ModeConfig mc = plugin.registry().mode(mid);
                    String sec = mc == null ? null : sectionForType(mc.type);
                    if (sec != null) secs.add(sec);
                }
                for (String sec : secs) {
                    s.sendMessage(Msg.parse("<gold>" + TITLES.get(sec) + ":"));
                    for (Line line : SECTIONS.get(sec)) s.sendMessage(Msg.parse(render(line)));
                }
            }
        }
        for (Line line : SECTIONS.get("maps").subList(0, 5)) s.sendMessage(Msg.parse(render(line)));
        s.sendMessage(Msg.parse(sectionBar()));
    }

    private static String render(Line line) {
        return "<click:suggest_command:'" + line.insert().replace("'", "") + "'><hover:show_text:'Подставить в чат'><yellow>"
                + Msg.esc(line.shown()) + "</yellow></hover></click> <gray>— " + Msg.esc(line.desc());
    }

    private static String sectionBar() {
        StringBuilder sb = new StringBuilder("<gray>Разделы: ");
        for (var e : TITLES.entrySet())
            sb.append("<aqua><click:run_command:'/mgp help ").append(e.getKey()).append("'><hover:show_text:'/mgp help ").append(e.getKey())
                    .append("'>[").append(e.getValue()).append("]</hover></click></aqua> ");
        return sb.toString();
    }

    // ------------------------------------------------------------------ опечатки

    static final List<String> SETUP_SUBS = List.of("menu", "check", "help", "delete", "export", "create", "spawn", "delspawn", "spawns", "clearspawns",
            "spectator", "seekerspawn", "alsomode", "center", "chest", "wand", "trader", "modes", "maps", "teamspawn", "objective", "objectives",
            "fortress", "pos1", "pos2", "clearsel", "expand", "contract", "shift", "outset", "inset", "face", "box", "size", "sel", "optimize",
            "rename", "name", "biome", "plot", "plots", "delplot", "tpplot", "tpspawn", "wall", "arena", "show", "zone", "teamzone", "trigger",
            "script", "scripts", "waiting", "lobby", "info", "assault");

    static List<String> topLevel(CommandSender s) {
        List<String> l = new ArrayList<>(List.of("join", "leave", "menu", "help"));
        if (s.hasPermission("mgp.admin")) l.addAll(List.of("map", "maps", "check", "delete", "setup", "tools", "import", "start", "list", "reload", "panel",
                "optimize", "rename", "sethub", "hotbar", "wand", "shop", "kit", "npc", "arrow", "gun", "pack", "debug"));
        return l;
    }

    /** Неизвестная подкоманда: «может, ты имел в виду …» (по расстоянию Левенштейна) + ссылка на справку. */
    static void unknown(CommandSender s, String prefix, String typed, List<String> known) {
        String t = typed.toLowerCase(Locale.ROOT);
        List<String> close = new ArrayList<>();
        for (String k : known) if (k.startsWith(t) || t.startsWith(k) && t.length() - k.length() <= 2) close.add(k);
        if (close.isEmpty()) {
            int best = Integer.MAX_VALUE;
            for (String k : known) {
                int d = distance(t, k);
                if (d < best) { best = d; close.clear(); }
                if (d == best) close.add(k);
            }
            if (best > Math.max(2, t.length() / 3)) close.clear();
        }
        StringBuilder sb = new StringBuilder("<red>Нет команды " + Msg.esc(prefix + typed) + ".");
        if (!close.isEmpty()) {
            sb.append(" <gray>Может быть:");
            for (String c : close.subList(0, Math.min(4, close.size())))
                sb.append(" <yellow><click:suggest_command:'").append(prefix).append(c).append(" '>").append(prefix).append(c).append("</click></yellow>");
        }
        s.sendMessage(Msg.parse(sb.toString()));
        s.sendMessage(Msg.parse("<gray>Справка: <aqua><click:run_command:'/mgp help'>[/mgp help]</click>"));
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    private Help() {}
}
