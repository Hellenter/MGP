package ru.mgp.command;

import org.bukkit.command.CommandSender;
import ru.mgp.MGPPlugin;
import ru.mgp.config.MapConfig;
import ru.mgp.config.ModeConfig;
import ru.mgp.util.Cfg;
import ru.mgp.util.Msg;
import ru.mgp.util.Pos;

import java.util.*;

/**
 * Чек-лист готовности карты (/mgp check): что режиму карты нужно и что уже есть.
 * Каждый пункт может нести команду исправления (подставляется в чат по клику) и «авто» (выполняется сразу).
 */
public final class MapCheck {
    public enum Level { OK, INFO, WARN, ERROR }

    public record Item(Level level, String text, String fix, String auto) {
        static Item ok(String t) { return new Item(Level.OK, t, null, null); }
    }

    private MapCheck() {}

    public static List<Item> run(MGPPlugin plugin, MapConfig m) {
        List<Item> out = new ArrayList<>();
        String id = m.id;
        boolean source = MapContext.sourceRegion(m) != null;
        // ---- общее
        if (plugin.worlds().templateExists(m.template)) out.add(Item.ok("Шаблон мира " + m.template));
        else out.add(new Item(Level.ERROR, "Нет шаблона мира templates/" + m.template,
                source ? "/mgp setup optimize " + id : "/mgp import " + id + " " + m.mode, source ? "/mgp setup optimize " + id : null));
        if (m.displayName.equals("<yellow>" + id)) out.add(new Item(Level.INFO, "Название для игроков не задано (сейчас «" + id + "»)", "/mgp setup name " + id + " ", null));
        else out.add(Item.ok("Название для игроков задано"));

        List<String> modes = new ArrayList<>();
        modes.add(m.mode);
        modes.addAll(m.alsoModes);
        Set<String> types = new LinkedHashSet<>();
        for (String mid : modes) {
            ModeConfig mc = plugin.registry().mode(mid);
            if (mc == null) { out.add(new Item(Level.ERROR, "Режима " + mid + " нет на сервере", "/mgp setup modes " + id, null)); continue; }
            if (!types.add(mc.type)) continue; // два режима одного типа (прятки и prop hunt) — проверки те же
            String head = modes.size() > 1 ? "[" + mc.id + "] " : "";
            switch (mc.type) {
                case "hunger_games" -> hungerGames(out, m, mc, head, source);
                case "build_battle" -> buildBattle(out, m, mc, head);
                case "hide_and_seek" -> hideAndSeek(out, m, mc, head);
                case "siege" -> siege(out, m, mc, head);
                case "assault" -> assault(out, m, mc, head);
                default -> spawnsAtLeast(out, m, null, 1, mc.maxPlayers, head);
            }
        }

        // ---- play-зона: спавны за её пределами
        if (!m.playZone.isEmpty()) {
            int outside = 0;
            for (Pos sp : allSpawns(m)) if (m.playZone.stream().noneMatch(r -> r.contains(sp.bx(), sp.by(), sp.bz()))) outside++;
            if (outside > 0) out.add(new Item(Level.ERROR, "Спавнов за play-зоной: " + outside + " — игроков будет выталкивать обратно",
                    "/mgp setup show " + id, null));
            else out.add(Item.ok("Play-зона: " + m.playZone.size() + " обл., все спавны внутри"));
        }
        if (m.spectator == null) out.add(new Item(Level.INFO, "Точка наблюдателей не задана — наблюдатели появятся на спавне", "/mgp setup spectator " + id, null));
        else out.add(Item.ok("Точка наблюдателей"));
        return out;
    }

    // ------------------------------------------------------------------ режимы

    private static void hungerGames(List<Item> out, MapConfig m, ModeConfig mc, String h, boolean source) {
        spawnsAtLeast(out, m, null, Math.max(1, mc.minPlayers), mc.maxPlayers, h);
        if (mc.chestLoot()) {
            if (!m.chests.isEmpty()) out.add(Item.ok(h + "Сундуков с лутом: " + m.chests.size()));
            else if (source) out.add(new Item(Level.OK, h + "Сундуки найдутся сами при первой игре (по маркерам или типу блока)", "/mgp debug " + mc.id + " " + m.id, null));
            else out.add(new Item(Level.ERROR, h + "Сундуки не заданы, а найти их негде (карта не из /mgp import)", "/mgp setup chest " + m.id + " ", null));
        }
        if (m.center == null) out.add(new Item(Level.WARN, h + "Центр карты не задан — барьер (worldborder) не сужается к ней",
                "/mgp setup center " + m.id + " ", "/mgp setup center " + m.id + " auto"));
        else out.add(Item.ok(h + "Центр " + m.center.serialize() + (m.borderSize > 0 ? ", барьер " + (int) m.borderSize : "")));
    }

    private static void buildBattle(List<Item> out, MapConfig m, ModeConfig mc, String h) {
        int n = m.plots.size();
        if (n == 0) out.add(new Item(Level.ERROR, h + "Нет участков: выдели участок и встань на его спавн", "/mgp setup plot " + m.id, null));
        else if (n < mc.maxPlayers) out.add(new Item(Level.WARN, h + "Участков " + n + " из " + mc.maxPlayers + " (max-players) — лишние игроки будут только смотреть", "/mgp setup plot " + m.id, null));
        else out.add(Item.ok(h + "Участков: " + n));
        if (n >= 2 && m.walls.isEmpty()) out.add(new Item(Level.WARN, h + "Нет стен между участками", "/mgp setup wall " + m.id + " auto", "/mgp setup wall " + m.id + " auto"));
        else if (n >= 2) out.add(Item.ok(h + "Стен: " + m.walls.size()));
        if (m.arena == null) out.add(new Item(Level.WARN, h + "Центр арены не задан — после стройки всех перенесёт на точку наблюдателей", "/mgp setup arena " + m.id, null));
        else out.add(Item.ok(h + "Центр арены"));
    }

    private static void hideAndSeek(List<Item> out, MapConfig m, ModeConfig mc, String h) {
        spawnsAtLeast(out, m, "hider", 1, 0, h);
        boolean room = m.yaml.getString("seeker-spawn") != null;
        int seeker = teamCount(m, "seeker");
        if (room) out.add(Item.ok(h + "Комната ищущих"));
        else if (seeker > 0) out.add(Item.ok(h + "Спавнов ищущих: " + seeker));
        else out.add(new Item(Level.WARN, h + "Нет комнаты ищущих — ищущие появятся вместе с прячущимися", "/mgp setup seekerspawn " + m.id, null));
    }

    private static void siege(List<Item> out, MapConfig m, ModeConfig mc, String h) {
        if (m.fortress.isEmpty()) out.add(new Item(Level.ERROR, h + "Нет крепости: выдели её и добавь", "/mgp setup fortress " + m.id, null));
        else out.add(Item.ok(h + "Крепость: " + m.fortress.size() + " ч."));
        teamSpawns(out, m, h, "defenders", "защиты");
        teamSpawns(out, m, h, "attackers", "атаки");
        if (m.teamZones.isEmpty()) out.add(new Item(Level.INFO, h + "Стартовых зон команд нет — на подготовке ходить можно везде", "/mgp setup teamzone " + m.id + " defenders", null));
        else out.add(Item.ok(h + "Зоны команд: " + String.join(", ", m.teamZones.keySet())));
    }

    private static void assault(List<Item> out, MapConfig m, ModeConfig mc, String h) {
        if (m.objectives.isEmpty()) out.add(new Item(Level.ERROR, h + "Нет целей: выдели постройку и создай цель", "/mgp setup objective " + m.id + " ", null));
        else out.add(Item.ok(h + "Целей: " + m.objectives.size()));
        List<Map<?, ?>> stages = m.yaml.getMapList("stages");
        if (stages.isEmpty()) {
            out.add(new Item(Level.INFO, h + "Стадий нет — будет одна стадия со всеми целями", "/mgp setup assault " + m.id + " stage 1 ", null));
            teamSpawns(out, m, h, "attackers", "атаки");
            teamSpawns(out, m, h, "defenders", "защиты");
        }
        for (int i = 0; i < stages.size(); i++) {
            int n = i + 1;
            Map<String, Object> s = Cfg.asMap(stages.get(i));
            String group = Cfg.str(s, "spawn-group", "stage" + n);
            List<String> objs = Cfg.strings(s.get("objectives"));
            String st = h + "Стадия " + n + ": ";
            if (objs.isEmpty()) out.add(new Item(Level.ERROR, st + "нет целей", "/mgp setup assault " + m.id + " stage " + n + " objective ", null));
            for (String o : objs) if (m.objectives.stream().noneMatch(x -> x.id().equals(o)))
                out.add(new Item(Level.ERROR, st + "цели «" + o + "» нет на карте", "/mgp setup objective " + m.id + " " + o + " ", null));
            for (String team : List.of("attackers", "defenders")) {
                int c = 0;
                for (MapConfig.SpawnPoint sp : m.spawnPoints) if (team.equals(sp.team()) && group.equalsIgnoreCase(String.valueOf(sp.group()))) c++;
                if (c == 0) out.add(new Item(n == 1 ? Level.ERROR : Level.WARN, st + "нет спавна " + (team.equals("attackers") ? "атаки" : "защиты"),
                        "/mgp setup assault " + m.id + " stage " + n + " spawn " + team, null));
            }
            if (!objs.isEmpty()) out.add(Item.ok(st + Cfg.str(s, "time", "время по умолчанию") + ", целей " + objs.size()));
        }
        var lb = m.yaml.getConfigurationSection("limbo");
        for (String team : List.of("attackers", "defenders")) {
            if (lb == null || lb.getString(team) == null)
                out.add(new Item(Level.WARN, h + "Нет лимбо " + (team.equals("attackers") ? "атаки" : "защиты") + " — после смерти сразу на спавн, без магазина",
                        "/mgp setup assault " + m.id + " limbo " + team, null));
        }
        if (lb != null && lb.getString("attackers") != null && lb.getString("defenders") != null) out.add(Item.ok(h + "Лимбо обеих команд"));
    }

    // ------------------------------------------------------------------ помощники

    private static void teamSpawns(List<Item> out, MapConfig m, String h, String team, String label) {
        int own = teamCount(m, team), common = commonCount(m);
        if (own > 0) out.add(Item.ok(h + "Спавнов " + label + ": " + own));
        else if (common > 0) out.add(new Item(Level.WARN, h + "Нет своих спавнов " + label + " — команда появится на общих", "/mgp setup teamspawn " + m.id + " " + team, null));
        else out.add(new Item(Level.ERROR, h + "Нет спавнов " + label, "/mgp setup teamspawn " + m.id + " " + team, null));
    }

    /** Спавны для команды team (свои, иначе общие); null — только общие. */
    private static void spawnsAtLeast(List<Item> out, MapConfig m, String team, int min, int max, String h) {
        int n = team != null && teamCount(m, team) > 0 ? teamCount(m, team) : commonCount(m);
        String fix = "/mgp setup spawn " + m.id;
        if (n == 0) out.add(new Item(Level.ERROR, h + "Нет спавнов игроков", fix, null));
        else if (n < min) out.add(new Item(Level.ERROR, h + "Спавнов " + n + ", а нужно минимум " + min, fix, null));
        else if (max > 0 && n < max) out.add(new Item(Level.WARN, h + "Спавнов " + n + " из " + max + " (max-players) — лишние игроки встанут по двое", fix, null));
        else out.add(Item.ok(h + "Спавнов: " + n));
    }

    private static int teamCount(MapConfig m, String team) {
        int c = 0;
        for (MapConfig.SpawnPoint sp : m.spawnPoints) if (team.equals(sp.team())) c++;
        return c;
    }

    private static int commonCount(MapConfig m) {
        int c = m.spawns.size();
        for (MapConfig.SpawnPoint sp : m.spawnPoints) if (sp.team() == null) c++;
        return c;
    }

    static List<Pos> allSpawns(MapConfig m) {
        List<Pos> l = new ArrayList<>(m.spawns);
        for (MapConfig.SpawnPoint sp : m.spawnPoints) l.add(sp.pos());
        return l;
    }

    public static int count(List<Item> items, Level level) {
        int c = 0;
        for (Item i : items) if (i.level() == level) c++;
        return c;
    }

    /** Короткая сводка: «готова» / «2 проблемы, 1 предупреждение». */
    public static String summary(List<Item> items) {
        int e = count(items, Level.ERROR), w = count(items, Level.WARN);
        if (e == 0 && w == 0) return "<green>готова";
        List<String> parts = new ArrayList<>();
        if (e > 0) parts.add("<red>проблем " + e);
        if (w > 0) parts.add("<yellow>предупреждений " + w);
        return String.join("<gray>, ", parts);
    }

    public static void print(MGPPlugin plugin, CommandSender s, MapConfig m) {
        List<Item> items = run(plugin, m);
        Msg.send(s, "<gold>Готовность карты <yellow>" + Msg.esc(m.id) + "</yellow> <gray>(" + Msg.esc(String.join(", ", modesOf(m))) + "): " + summary(items));
        // сначала проблемы, потом предупреждения, потом остальное
        List<Item> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingInt((Item i) -> switch (i.level()) { case ERROR -> 0; case WARN -> 1; case INFO -> 2; case OK -> 3; }));
        for (Item i : sorted) {
            String icon = switch (i.level()) {
                case OK -> "<green>✔ <gray>";
                case INFO -> "<aqua>• <gray>";
                case WARN -> "<yellow>⚠ <white>";
                case ERROR -> "<red>✘ <white>";
            };
            StringBuilder sb = new StringBuilder(icon).append(Msg.esc(i.text()));
            if (i.level() != Level.OK && i.fix() != null)
                sb.append(" <aqua><click:suggest_command:'").append(i.fix()).append("'><hover:show_text:'").append(Msg.esc(i.fix()))
                        .append("'>[исправить]</hover></click></aqua>");
            if (i.auto() != null)
                sb.append(" <green><click:run_command:'").append(i.auto()).append("'><hover:show_text:'Сделать автоматически: ")
                        .append(Msg.esc(i.auto())).append("'>[авто]</hover></click></green>");
            s.sendMessage(Msg.parse(sb.toString()));
        }
        s.sendMessage(Msg.parse("<dark_gray>» <aqua><click:run_command:'/mgp setup show " + m.id + "'>[показать зоны]</click></aqua> "
                + "<aqua><click:run_command:'/mgp setup menu " + m.id + "'>[меню карты]</click></aqua> "
                + "<aqua><click:run_command:'/mgp check " + m.id + "'>[обновить]</click></aqua>"));
    }

    static List<String> modesOf(MapConfig m) {
        List<String> l = new ArrayList<>();
        l.add(m.mode);
        l.addAll(m.alsoModes);
        return l;
    }

}
