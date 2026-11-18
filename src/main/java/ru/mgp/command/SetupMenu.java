package ru.mgp.command;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.mgp.MGPPlugin;
import ru.mgp.config.MapConfig;
import ru.mgp.config.ModeConfig;
import ru.mgp.ui.Menu;
import ru.mgp.util.Items;
import ru.mgp.util.Msg;

import java.util.*;

/**
 * Меню карты (/mgp setup без аргументов, или ПКМ «Меню карты» из /mgp tools).
 * Всё, что можно сделать с картой, — кнопками: точки там, где стоишь; зоны из выделения (только подходящие режиму карты);
 * автоисправления из чек-листа; режимы, название, подсветка, телепорт. Кнопки выполняют обычные команды /mgp setup,
 * так что результат и сообщения те же.
 */
final class SetupMenu extends Menu {
    private final MGPPlugin plugin;
    private final MgpCommand command;
    private final MapConfig map;

    SetupMenu(MGPPlugin plugin, MgpCommand command, MapConfig map, Player viewer) {
        super(6, "<dark_gray>Карта " + map.id);
        this.plugin = plugin;
        this.command = command;
        this.map = map;
        String id = map.id;
        List<MapCheck.Item> check = MapCheck.run(plugin, map);
        Set<String> types = types(plugin, map);

        // ---- верхняя строка: карта
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Режимы: <white>" + String.join(", ", MapCheck.modesOf(map)));
        lore.add("<gray>Шаблон: <white>" + map.template);
        lore.add("<gray>Готовность: " + MapCheck.summary(check));
        int shown = 0;
        for (MapCheck.Item i : check) {
            if (i.level() != MapCheck.Level.ERROR && i.level() != MapCheck.Level.WARN) continue;
            if (shown++ >= 6) { lore.add("<dark_gray>…"); break; }
            lore.add((i.level() == MapCheck.Level.ERROR ? "<red>✘ " : "<yellow>⚠ ") + "<gray>" + Msg.esc(i.text()));
        }
        lore.add("");
        lore.add("<aqua>Клик — чек-лист в чат (с кнопками исправления)");
        set(0, Items.icon(map.icon, map.displayName + " <dark_gray>(" + id + ")", lore, MapCheck.count(check, MapCheck.Level.ERROR) == 0), p -> run(p, "mgp check " + id, true));
        set(2, Items.icon(Material.ENDER_PEARL, "<white>Телепорт на карту", List.of("<gray>/mgp map tp " + id), false), p -> run(p, "mgp map tp " + id, true));
        set(3, Items.icon(Material.SPYGLASS, "<white>Показать зоны частицами", List.of("<gray>участки, стены, зоны, крепость, цели — 60 сек.", "<gray>/mgp setup show " + id), false),
                p -> run(p, "mgp setup show " + id, true));
        set(4, Items.icon(Material.WRITABLE_BOOK, "<white>Чек-лист", List.of("<gray>что настроено и чего не хватает", "<gray>/mgp check " + id), false), p -> run(p, "mgp check " + id, true));
        set(5, Items.icon(Material.COMPARATOR, "<white>Режимы карты", List.of("<gray>в каких режимах играется", "<gray>/mgp setup modes " + id), false),
                p -> new MapModesMenu(plugin, plugin.registry().map(id)).open(p));
        set(6, Items.icon(Material.NAME_TAG, "<white>Название для игроков", List.of("<gray>сейчас: " + map.displayName, "<gray>Клик — написать новое в чат"), false),
                p -> command.tools().prompt(p, "Название карты (можно с цветом: " + Msg.esc("<green>Джунгли") + "):", t -> p.performCommand("mgp setup name " + id + " " + t)));
        set(7, Items.icon(Material.CHEST, "<white>Набор строителя", List.of("<gray>топор, жезл точек, меню карты, палочка", "<gray>/mgp tools"), false), p -> run(p, "mgp tools", true));
        set(8, Items.icon(Material.BOOK, "<white>Справка по режиму", List.of("<gray>/mgp help"), false), p -> run(p, "mgp help", true));

        // ---- точки там, где стоишь
        set(9, Items.icon(Material.ARMOR_STAND, "<gold>Точки — там, где ты стоишь", List.of("<gray>Встань на место и нажми нужное.", "<gray>То же делает жезл точек (ПКМ)."), false), null);
        int slot = 10;
        for (BuilderTools.Target t : BuilderTools.targets(plugin, map)) {
            if (slot > 17) break;
            set(slot++, Items.icon(t.icon(), "<yellow>" + t.label(), List.of("<gray>/" + t.cmd()), false), p -> run(p, t.cmd(), false));
        }

        // ---- из выделения
        String sel = command.selectionSize(viewer);
        set(18, Items.icon(Material.GOLDEN_AXE, "<gold>Из выделения", sel != null
                ? List.of("<gray>Выделено: <white>" + sel, "<aqua>Клик — меню граней выделения")
                : List.of("<red>Ничего не выделено", "<gray>Возьми топор: ЛКМ и ПКМ по углам", "<aqua>Клик — взять топор"), sel != null),
                p -> run(p, sel != null ? "mgp setup sel" : "mgp setup wand", sel != null));
        List<Opt> opts = selectionOptions(types);
        slot = 19;
        for (Opt o : opts) {
            if (slot == 27) slot = 28;
            if (slot > 35) break;
            List<String> l = new ArrayList<>(o.lore());
            if (sel == null) l.add("<red>Сначала выдели область");
            set(slot++, Items.icon(o.icon(), (sel != null ? "<yellow>" : "<gray>") + o.label(), l, false), p -> {
                if (!command.hasSelection(p)) { p.closeInventory(); run(p, "mgp setup wand", true); return; }
                o.action().accept(p);
            });
        }

        // ---- авто
        set(36, Items.icon(Material.REDSTONE_TORCH, "<gold>Авто", List.of("<gray>Что плагин может сделать сам"), false), null);
        slot = 37;
        Set<String> autos = new LinkedHashSet<>();
        for (MapCheck.Item i : check) if (i.auto() != null) autos.add(i.auto() + "\u0000" + i.text());
        for (String a : autos) {
            if (slot > 44) break;
            String[] parts = a.split("\u0000", 2);
            String cmd = parts[0].substring(1);
            set(slot++, Items.icon(Material.LIME_DYE, "<green>Исправить: <white>" + Msg.esc(parts[1]), List.of("<gray>/" + cmd), false), p -> run(p, cmd, false));
        }
        if (types.contains("hunger_games") && map.center != null && slot <= 44)
            set(slot++, Items.icon(Material.BEACON, "<white>Пересчитать центр и барьер по спавнам", List.of("<gray>/mgp setup center " + id + " auto"), false),
                    p -> run(p, "mgp setup center " + id + " auto", false));
        if (types.contains("build_battle") && map.plots.size() >= 2 && slot <= 44)
            set(slot++, Items.icon(Material.BRICKS, "<white>Найти стены между участками заново", List.of("<gray>/mgp setup wall " + id + " auto"), false),
                    p -> run(p, "mgp setup wall " + id + " auto", false));
        if (slot == 37) set(37, Items.icon(Material.GRAY_DYE, "<gray>Нечего исправлять автоматически", List.of(), false), null);

        // ---- низ: списки
        set(45, Items.icon(Material.PAPER, "<white>Список спавнов", List.of("<gray>с кнопками [тп] и [удалить]", "<gray>/mgp setup spawns " + id), false), p -> run(p, "mgp setup spawns " + id, true));
        if (types.contains("assault"))
            set(46, Items.icon(Material.TNT, "<white>Стадии штурма", List.of("<gray>/mgp setup assault " + id + " info"), false), p -> run(p, "mgp setup assault " + id + " info", true));
        if (types.contains("siege"))
            set(46, Items.icon(Material.STONE_BRICKS, "<white>Части крепости", List.of("<gray>/mgp setup fortress " + id + " list"), false), p -> run(p, "mgp setup fortress " + id + " list", true));
        if (types.contains("build_battle"))
            set(46, Items.icon(Material.GRASS_BLOCK, "<white>Участки", List.of("<gray>/mgp setup plots " + id), false), p -> run(p, "mgp setup plots " + id, true));
        set(47, Items.icon(Material.BLAZE_ROD, "<white>Скрипты карты", List.of("<gray>/mgp setup scripts " + id), false), p -> run(p, "mgp setup scripts " + id, true));
        set(49, Items.icon(Material.BARRIER, "<red>Закрыть", List.of(), false), Player::closeInventory);
        set(53, Items.icon(Material.FILLED_MAP, "<white>Другая карта", List.of("<gray>/mgp map list"), false), p -> run(p, "mgp map list", true));
    }

    private record Opt(String label, Material icon, List<String> lore, java.util.function.Consumer<Player> action) {}

    private List<Opt> selectionOptions(Set<String> types) {
        String id = map.id;
        List<Opt> l = new ArrayList<>();
        l.add(new Opt("Play-зона (за неё не выйти)", Material.LIGHT_BLUE_STAINED_GLASS, List.of("<gray>областей может быть несколько", "<gray>/mgp setup zone " + id),
                p -> run(p, "mgp setup zone " + id, false)));
        for (String team : MgpCommand.teamsOf(map)) {
            if (team.equals("hider")) continue; // прячущимся стартовая зона не нужна
            l.add(new Opt("Стартовая зона: " + teamName(team), teamIcon(team), List.of("<gray>где команда стоит до открытия зон", "<gray>/mgp setup teamzone " + id + " " + team),
                    p -> run(p, "mgp setup teamzone " + id + " " + team, false)));
        }
        if (types.contains("siege"))
            l.add(new Opt("Часть крепости", Material.STONE_BRICKS, List.of("<gray>/mgp setup fortress " + id), p -> run(p, "mgp setup fortress " + id, false)));
        if (types.contains("assault")) {
            l.add(new Opt("Цель на разрушение", Material.TNT, List.of("<gray>спрошу id и процент в чате", "<gray>" + Msg.esc("/mgp setup objective " + id + " <id> [процент]")),
                    p -> command.tools().prompt(p, "id цели латиницей и, если нужно, процент разрушения: <yellow>bunker 60", t -> {
                        p.performCommand("mgp setup objective " + id + " " + t);
                        offerStageLink(p, t.split(" ")[0]);
                    })));
            l.add(new Opt("Зона команды в стадию…", Material.MAP, List.of("<gray>выбрать стадию и команду"), p -> stageZoneMenu(p).open(p)));
            l.add(new Opt("Выход из лимбо", Material.OAK_DOOR, List.of("<gray>кто войдёт — уходит в бой", "<gray>/mgp setup assault " + id + " exit"),
                    p -> run(p, "mgp setup assault " + id + " exit", false)));
        }
        if (types.contains("build_battle")) {
            l.add(new Opt("Участок (спавн — где стоишь)", Material.GRASS_BLOCK, List.of("<gray>/mgp setup plot " + id), p -> run(p, "mgp setup plot " + id, false)));
            l.add(new Opt("Стена вручную", Material.BRICKS, List.of("<gray>обычно хватает «авто»", "<gray>/mgp setup wall " + id), p -> run(p, "mgp setup wall " + id, false)));
        }
        l.add(new Opt("Зона-триггер", Material.TRIPWIRE_HOOK, List.of("<gray>из выделения: вход / внутри / выход,", "<gray>действия (телепорт и др.) — в меню", "<gray>" + Msg.esc("/mgp setup trigger " + id + " [id]")),
                p -> run(p, "mgp setup trigger " + id, false)));
        return l;
    }

    /** После создания цели на карте штурма — предложить сразу добавить её в стадию. */
    private void offerStageLink(Player p, String objective) {
        MapConfig m = plugin.registry().map(map.id);
        if (m == null || m.objectives.stream().noneMatch(o -> o.id().equals(objective))) return;
        int stages = m.yaml.getMapList("stages").size();
        StringBuilder sb = new StringBuilder("<gray>Добавить цель в стадию:");
        for (int n = 1; n <= stages + 1; n++)
            sb.append(" <aqua><click:run_command:'/mgp setup assault ").append(m.id).append(" stage ").append(n).append(" objective ").append(objective)
                    .append("'>[").append(n <= stages ? "стадия " + n : "новая стадия " + n).append("]</click></aqua>");
        p.sendMessage(Msg.parse(sb.toString()));
    }

    private Menu stageZoneMenu(Player viewer) {
        MapConfig m = plugin.registry().map(map.id);
        int stages = Math.max(1, m == null ? 1 : m.yaml.getMapList("stages").size());
        SetupMenu back = this;
        return new Menu(Math.min(6, (stages * 2 + 9) / 9 + 1), "<dark_gray>Зона в стадию — " + map.id) {
            {
                int slot = 0;
                for (int n = 1; n <= stages && slot < 45; n++) {
                    for (String team : List.of("attackers", "defenders")) {
                        String cmd = "mgp setup assault " + map.id + " stage " + n + " zone " + team;
                        set(slot++, Items.icon(teamIcon(team), "<yellow>Стадия " + n + ": " + teamName(team), List.of("<gray>/" + cmd), false), p -> {
                            p.closeInventory();
                            p.performCommand(cmd);
                        });
                    }
                }
                set(getInventory().getSize() - 1, Items.icon(Material.ARROW, "<gray>Назад", List.of(), false), back::open);
            }
        };
    }

    static String teamName(String team) {
        return switch (team) {
            case "attackers" -> "атака";
            case "defenders" -> "защита";
            case "seeker" -> "ищущие";
            case "hider" -> "прячущиеся";
            default -> team;
        };
    }

    static Material teamIcon(String team) {
        return switch (team) {
            case "attackers" -> Material.RED_BANNER;
            case "defenders" -> Material.BLUE_BANNER;
            case "seeker" -> Material.ORANGE_BANNER;
            case "hider" -> Material.LIME_BANNER;
            default -> Material.WHITE_BANNER;
        };
    }

    static Set<String> types(MGPPlugin plugin, MapConfig m) {
        Set<String> t = new LinkedHashSet<>();
        for (String id : MapCheck.modesOf(m)) {
            ModeConfig mc = plugin.registry().mode(id);
            t.add(mc == null ? id : mc.type);
        }
        return t;
    }

    /** Выполнить команду от игрока; close — закрыть меню (ответ нужен в чате), иначе открыть меню заново с новым состоянием. */
    private void run(Player p, String cmd, boolean close) {
        if (close) p.closeInventory();
        p.performCommand(cmd);
        if (!close) {
            MapConfig fresh = plugin.registry().map(map.id);
            if (fresh != null) Bukkit.getScheduler().runTask(plugin, () -> new SetupMenu(plugin, command, fresh, p).open(p));
        }
    }
}
