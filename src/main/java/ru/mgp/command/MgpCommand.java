package ru.mgp.command;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ru.mgp.MGPPlugin;
import ru.mgp.config.ConfigRegistry;
import ru.mgp.config.MapConfig;
import ru.mgp.config.ModeConfig;
import ru.mgp.session.GameSession;
import ru.mgp.ui.GameSelectorMenu;
import ru.mgp.util.Cfg;
import ru.mgp.util.Msg;
import ru.mgp.util.Pos;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** /mgp, /leave, /play и команды настройки карт /mgp setup ... */
public final class MgpCommand implements TabExecutor {
    private final MGPPlugin plugin;
    private final Map<UUID, Pos> pos1 = new HashMap<>();
    private final Map<UUID, Pos> pos2 = new HashMap<>();
    private final Map<UUID, String> posWorld = new HashMap<>();

    public MgpCommand(MGPPlugin plugin) {
        this.plugin = plugin;
        this.display = new SelectionDisplay(plugin, pos1, pos2, posWorld);
        this.selOps = new SelectionOps(pos1, pos2, posWorld, display::update);
    }

    private final SelectionOps selOps;

    private final SelectionDisplay display;

    /** Убрать рамки выделения (при выключении плагина). */
    public void shutdown() { display.removeAll(); }

    private boolean optimizeBorders() { return plugin.getConfig().getBoolean("import.optimize-borders", true); }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (name.equals("leave")) {
            if (sender instanceof Player p) plugin.sessions().leave(p);
            return true;
        }
        if (name.equals("play")) {
            if (sender instanceof Player p) {
                if (a.length > 0) plugin.sessions().join(p, a[0]);
                else new GameSelectorMenu(plugin).open(p);
            }
            return true;
        }
        if (a.length == 0 || a[0].equalsIgnoreCase("help")) { help(sender); return true; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "join" -> {
                if (sender instanceof Player p && a.length > 1) plugin.sessions().join(p, a[1]);
            }
            case "leave" -> { if (sender instanceof Player p) plugin.sessions().leave(p); }
            case "menu" -> { if (sender instanceof Player p) new GameSelectorMenu(plugin).open(p); }
            case "list" -> {
                if (!admin(sender)) return true;
                Msg.send(sender, "<yellow>Активные сессии: " + plugin.sessions().sessions().size());
                for (GameSession s : plugin.sessions().sessions()) {
                    Msg.send(sender, "<gray>#" + s.id + " " + s.mode.id + " — " + s.state() + ", игроков " + s.size()
                            + (s.map() != null ? ", карта " + s.map().id : ""));
                }
            }
            case "start" -> {
                if (!admin(sender) || !(sender instanceof Player p)) return true;
                GameSession s = plugin.sessions().of(p);
                if (s == null || !s.forceStart()) Msg.send(p, "<red>Ты не в лобби ожидания.");
            }
            case "debug" -> { if (admin(sender)) debug(sender, a); }
            case "reload" -> {
                if (!admin(sender)) return true;
                plugin.reloadAll();
                Msg.send(sender, "<green>Конфиги перезагружены (идущие игры доиграют со старыми настройками).");
            }
            case "setup" -> { if (admin(sender)) setup(sender, Arrays.copyOfRange(a, 1, a.length)); }
            case "import" -> { if (admin(sender)) importMap(sender, a); }
            case "sethub" -> {
                if (!admin(sender)) return true;
                if (!(sender instanceof Player p)) { Msg.send(sender, "Только в игре."); return true; }
                if (p.getWorld().getName().startsWith(ru.mgp.world.WorldService.PREFIX)) { Msg.send(p, "<red>Это игровой мир сессии — встань в мире хаба."); return true; }
                Pos pos = Pos.of(p.getLocation());
                plugin.getConfig().set("hub.world", p.getWorld().getName());
                plugin.getConfig().set("hub.spawn", pos.serialize());
                plugin.saveConfig();
                Msg.send(p, "<green>Спавн хаба: " + p.getWorld().getName() + " " + pos.serialize() + ". Сюда попадают при входе, после игры и по /hub.");
            }
            case "rename" -> { if (admin(sender)) renameMap(sender, a); }
            case "shop", "shops" -> {
                if (!admin(sender)) return true;
                if (!(sender instanceof Player p)) { Msg.send(sender, "Только в игре."); return true; }
                if (a.length > 1) {
                    ru.mgp.shop.Shop sh = plugin.shops().get(a[1]);
                    if (sh == null) sh = plugin.shops().create(a[1]);
                    if (sh == null) { Msg.send(p, "<red>Неверный id магазина"); return true; }
                    new ru.mgp.shop.ShopEditMenu(plugin.shops(), sh, 0).open(p);
                } else new ru.mgp.shop.ShopListMenu(plugin.shops()).open(p);
            }
            case "wand" -> {
                if (!admin(sender)) return true;
                if (!(sender instanceof Player p)) { Msg.send(sender, "Только в игре."); return true; }
                plugin.wand().give(p, a.length > 1 ? a[1] : null);
            }
            case "hotbar" -> {
                if (!admin(sender) || !(sender instanceof Player p)) return true;
                boolean hide = a.length > 1 ? a[1].equalsIgnoreCase("off") : !ru.mgp.ui.Hotbar.hubHidden(p);
                ru.mgp.ui.Hotbar.setHubHidden(p, hide);
                if (hide) {
                    ru.mgp.ui.Hotbar.clearTagged(p);
                    Msg.send(p, "<green>Предметы хаба выключены для тебя (и после перезахода). Вернуть: /mgp hotbar on");
                } else {
                    if (plugin.sessions().of(p) == null) ru.mgp.ui.Hotbar.giveHub(p);
                    Msg.send(p, "<green>Предметы хаба снова включены." + (p.getGameMode() == org.bukkit.GameMode.CREATIVE ? " <gray>В креативе их нет — появятся, когда выйдешь из него." : ""));
                }
            }
            case "optimize" -> {
                if (!admin(sender)) return true;
                try { optimizeMap(sender, a); } catch (IllegalArgumentException e) { Msg.send(sender, "<red>" + Msg.esc(String.valueOf(e.getMessage()))); }
            }
            case "panel" -> {
                if (!admin(sender)) return true;
                if (!plugin.panel().running()) { Msg.send(sender, "<red>Админ-панель выключена (panel.enabled в config.yml)."); return true; }
                String url = plugin.panel().url(true);
                sender.sendMessage(Msg.parse("<gold>Админ-панель: </gold>").append(
                        net.kyori.adventure.text.Component.text("[открыть]")
                                .color(net.kyori.adventure.text.format.NamedTextColor.GREEN)
                                .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(url))));
                Msg.send(sender, "<gray>Ссылка содержит ключ доступа — не показывай её другим.");
                if (!(sender instanceof Player)) sender.sendMessage(url);
            }
            default -> help(sender);
        }
        return true;
    }

    /** /mgp debug [режим] — что плагин на самом деле видит: тиры сундуков, лут, ломаемые блоки, состояние игрока. */
    private void debug(CommandSender s, String[] a) {
        Msg.send(s, "<gold>Тиры сундуков: <white>" + ru.mgp.util.Cfg.chestTiers(plugin.getConfig()));
        String modeId = a.length > 1 ? a[1] : "hunger_games";
        ModeConfig m = plugin.registry().mode(modeId);
        if (m == null) { Msg.send(s, "<red>Нет режима " + Msg.esc(modeId)); return; }
        Msg.send(s, "<gold>Таблицы лута " + m.id + ": <white>" + m.loot.keySet());
        List<String> unknown = new ArrayList<>();
        Set<org.bukkit.Material> br = ru.mgp.util.Cfg.materials(m.settings.getStringList("breakable-blocks"), unknown);
        StringBuilder sample = new StringBuilder();
        for (String n : List.of("OAK_LEAVES", "VINE", "SHORT_GRASS", "TALL_GRASS", "POPPY", "COBWEB", "CAVE_VINES", "STONE")) {
            org.bukkit.Material mat = org.bukkit.Material.matchMaterial(n);
            sample.append(n).append(mat != null && br.contains(mat) ? "<green>✔</green> " : "<red>✘</red> ");
        }
        Msg.send(s, "<gold>Ломаемых блоков: <white>" + br.size() + "</white> " + sample + (unknown.isEmpty() ? "" : "<red>не распознано: " + Msg.esc(unknown.toString())));
        MapConfig map = a.length > 2 ? plugin.registry().map(a[2]) : null;
        if (map != null) {
            ru.mgp.util.Pos mn = ru.mgp.util.Pos.parse(map.yaml.getString("source.min")), mx = ru.mgp.util.Pos.parse(map.yaml.getString("source.max"));
            if (mn == null || mx == null) Msg.send(s, "<yellow>У карты " + map.id + " нет source.min/max — автопоиск сундуков недоступен");
            else {
                try {
                    var r = ru.mgp.util.Region.of(mn, mx);
                    var dim = ru.mgp.world.RegionCutter.findOverworldDir(plugin.worlds().templatesDir().resolve(map.template));
                    Map<String, String> tiers = ru.mgp.util.Cfg.chestTiers(plugin.getConfig());
                    Map<String, Integer> byTier = new TreeMap<>();
                    for (var f : ru.mgp.world.ChestScanner.scan(dim, r.minX(), r.minZ(), r.maxX(), r.maxZ())) {
                        String t = tiers.get(f.type());
                        if (t != null) byTier.merge(t + (m.loot.containsKey(t) ? "" : "(нет таблицы!)"), 1, Integer::sum);
                    }
                    Msg.send(s, "<gold>Сундуки в шаблоне " + map.template + ": <white>" + byTier + "</white>, в списке карты: " + map.chests.size());
                } catch (Exception ex) {
                    Msg.send(s, "<red>Ошибка чтения шаблона: " + Msg.esc(String.valueOf(ex.getMessage())));
                }
            }
        }
        if (s instanceof Player p) {
            GameSession gs = plugin.sessions().of(p);
            Msg.send(p, "<gold>Ты: <white>режим игры " + p.getGameMode() + ", мир " + p.getWorld().getName()
                    + (gs == null ? ", не в сессии" : ", сессия " + gs.state() + (gs.isAlive(p) ? ", жив" : ", не жив")));
            org.bukkit.block.Block b = p.getTargetBlockExact(6);
            if (b != null) Msg.send(p, "<gold>Смотришь на: <white>" + b.getType() + (br.contains(b.getType()) ? " <green>(можно ломать)" : " <red>(нельзя ломать)"));
        }
    }

    private boolean admin(CommandSender s) {
        if (s.hasPermission("mgp.admin")) return true;
        Msg.send(s, "<red>Нет прав.");
        return false;
    }

    private void help(CommandSender s) {
        Msg.send(s, "<yellow>/play [режим]</yellow> — меню игр или быстрый вход");
        Msg.send(s, "<yellow>/leave</yellow> — выйти в лобби");
        if (s.hasPermission("mgp.admin")) {
            Msg.send(s, "<gold>/mgp start | list | reload | panel");
            Msg.send(s, "<gold>/mgp hotbar [on|off]</gold> — предметы хаба (выбор игры, серверы) у себя; в креативе их нет автоматически");
            Msg.send(s, "<gold>/mgp sethub</gold> — спавн хаба там, где ты стоишь (с поворотом головы)");
            Msg.send(s, "<gold>/mgp setup</gold> — список команд настройки карт");
            Msg.send(s, "<gold>/mgp import <карта> <режим> <x1 y1 z1 x2 y2 z2> [мир] [название]</gold> — вырезать готовую карту из мира");
            Msg.send(s, "<gray>   без координат берётся выделение /mgp setup pos1 и pos2");
            Msg.send(s, "<gold>/mgp shop [id]</gold> — магазины в стиле Bed Wars: редактор в игре, продавцы на картах");
            Msg.send(s, "<gold>/mgp wand [карта]</gold> — палочка скриптов: ЛКМ по блоку — телепорты, кнопки, ловушки без командных блоков");
            Msg.send(s, "<gold>/mgp rename <карта> <новый id> [название]</gold> — переименовать карту");
            Msg.send(s, "<gold>/mgp optimize <карта></gold> — оптимизация границ готовой карты: обрезать края, где только воздух");
        }
    }

    // ------------------------------------------------------------------ setup

    private void setup(CommandSender sender, String[] a) {
        if (!(sender instanceof Player p)) { Msg.send(sender, "Только в игре."); return; }
        if (a.length == 0) {
            for (String line : List.of(
                    "<gold>Настройка карт (стой в мире карты, координаты берутся с тебя):",
                    "<yellow>/mgp setup export <шаблон></yellow> — скопировать текущий мир в templates/",
                    "<yellow>/mgp setup create <карта> <режим> <шаблон>",
                    "<yellow>/mgp setup spawn <карта></yellow> — добавить спавн игрока",
                    "<yellow>/mgp setup delspawn <карта> [номер|last]</yellow> — удалить ближайший к тебе спавн (или по номеру)",
                    "<yellow>/mgp setup spawns <карта></yellow> — список спавнов с номерами",
                    "<yellow>/mgp setup clearspawns <карта></yellow> — удалить все спавны",
                    "<yellow>/mgp setup spectator <карта></yellow> — точка наблюдателей",
                    "<yellow>/mgp setup seekerspawn <карта> [none]</yellow> — комната ищущих в прятках",
                    "<yellow>/mgp setup modes <карта> [режим...]</yellow> — в каких режимах играется карта (без режимов — меню); <yellow>/mgp setup maps</yellow> — карты по режимам",
                    "<yellow>/mgp setup alsomode <карта> <режим|-режим></yellow> — карта играется и в другом режиме",
                    "<yellow>/mgp setup center <карта> [размер барьера]",
                    "<yellow>/mgp setup chest <карта> <тир></yellow> — сундук, на который смотришь",
                    "<yellow>/mgp setup trader <карта> <набор сделок> [профессия]",
                    "<yellow>/mgp setup pos1 | pos2</yellow> — углы области (рамка видна частицами)",
                    "<yellow>/mgp setup expand <n> [обратно] [направление]</yellow> — расширить как //expand (vert — на всю высоту); contract, shift, outset, inset",
                    "<yellow>/mgp setup teamspawn <карта> <команда|all> [группа] [locked]</yellow> — спавн команды (seeker, hider...) или закрытый до триггера",
                    "<yellow>/mgp setup fortress <карта> [clear]</yellow> — крепость для осады из выделения (спавны: teamspawn defenders / attackers)",
                    "<yellow>/mgp setup objective <карта> <id> <процент></yellow> — цель на разрушение из выделения; <yellow>objective ... action</yellow>, <yellow>objectives <карта></yellow>",
                    "<yellow>/mgp setup sel</yellow> — меню граней выделения; <yellow>face <грань> <коорд.|+n|-n|here></yellow>, <yellow>box x1 y1 z1 x2 y2 z2</yellow>",
                    "<yellow>/mgp setup clearsel</yellow> — убрать выделение и рамку",
                    "<yellow>/mgp setup optimize <карта></yellow> — оптимизация границ: обрезать пустые края (только воздух)",
                    "<yellow>/mgp setup rename <карта> <новый id> [название]</yellow> — переименовать карту",
                    "<yellow>/mgp setup name <карта> <название></yellow> — только название для игроков (MiniMessage, например <green>Джунгли)",
                    "<yellow>/mgp setup plot <карта></yellow> — участок из pos1/pos2, спавн = ты",
                    "<yellow>/mgp setup trigger <карта> <имя> [once|per_player|repeat]",
                    "<yellow>/mgp setup waiting <режим></yellow> — точка в лобби ожидания",
                    "<yellow>/mgp setup lobby <режим></yellow> — вырезать лобби ожидания из pos1/pos2, спавн = ты",
                    "<yellow>/mgp setup lobby default</yellow> — общее лобби ожидания для всех режимов без своего",
                    "<yellow>/mgp setup biome <карта> <биом|none></yellow> — биом на всю карту",
                    "<yellow>/mgp setup script <карта> <id> hold|button|plate|zone</yellow> — скрипт на блоке, на который смотришь",
                    "<yellow>/mgp setup script <карта> <id> tphere | action <действие> | set <ключ> <знач> | clear | remove",
                    "<yellow>/mgp setup scripts <карта></yellow> — список скриптов карты",
                    "<yellow>/mgp setup info <карта>")) Msg.send(p, line);
            return;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        Location loc = p.getLocation();
        try {
            switch (sub) {
                case "export" -> {
                    need(a, 2);
                    Msg.send(p, "<gray>Копирую мир " + p.getWorld().getName() + "...");
                    plugin.worlds().exportTemplate(p.getWorld(), a[1]).whenComplete((v, ex) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (ex != null) Msg.send(p, "<red>Ошибка: " + Msg.esc(String.valueOf(ex.getMessage())));
                        else Msg.send(p, "<green>Шаблон '" + a[1] + "' сохранён.");
                    }));
                }
                case "create" -> {
                    need(a, 4);
                    a[1] = ConfigRegistry.cleanId(a[1]);
                    if (!a[1].matches("[a-z0-9_]{1,32}")) { Msg.send(p, "<red>Id карты: только a-z, 0-9 и _ (до 32 символов)"); return; }
                    File f = plugin.registry().mapFile(a[1]);
                    if (f.exists()) { Msg.send(p, "<red>Карта уже существует."); return; }
                    if (plugin.registry().mode(a[2]) == null) { Msg.send(p, "<red>Нет режима " + a[2]); return; }
                    YamlConfiguration y = new YamlConfiguration();
                    y.set("mode", a[2].toLowerCase(Locale.ROOT));
                    y.set("display-name", "<yellow>" + a[1]);
                    y.set("icon", "FILLED_MAP");
                    y.set("template", a[3]);
                    y.set("spawns", new ArrayList<String>());
                    save(y, f);
                    plugin.registry().loadMap(f);
                    Msg.send(p, "<green>Карта создана: maps/" + f.getName());
                }
                case "spawn" -> edit(p, a, 2, y -> addString(y, "spawns", Pos.of(loc).serialize()), "Спавн добавлен");
                case "delspawn" -> deleteSpawn(p, a);
                case "spawns" -> listSpawns(p, a);
                case "teamspawn" -> {
                    // /mgp setup teamspawn <карта> <команда|all> [группа] [locked]
                    need(a, 3);
                    String team = a[2].equalsIgnoreCase("all") || a[2].equals("-") ? null : a[2].toLowerCase(Locale.ROOT);
                    String group = a.length > 3 && !a[3].equalsIgnoreCase("locked") ? a[3].toLowerCase(Locale.ROOT) : null;
                    boolean locked = Arrays.stream(a).skip(3).anyMatch(x -> x.equalsIgnoreCase("locked"));
                    edit(p, a, 3, y -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("pos", Pos.of(loc).serialize());
                        if (team != null) m.put("team", team);
                        if (group != null) m.put("group", group);
                        if (locked) m.put("enabled", false);
                        addMap(y, "spawn-points", m);
                    }, "Спавн добавлен: " + (team == null ? "для всех" : "команда " + team)
                            + (group == null ? "" : ", группа " + group) + (locked ? " <gray>(закрыт до действия <yellow>spawns: enable " + (group == null ? "<группа>" : group) + "</yellow>)" : ""));
                    if (locked && group == null) Msg.send(p, "<red>Закрытому спавну нужна группа, иначе его нечем открыть: /mgp setup teamspawn <карта> <команда> <группа> locked");
                }
                case "objective" -> objective(p, a);
                case "fortress" -> {
                    // /mgp setup fortress <карта> [clear] — область крепости для осады из выделения pos1/pos2
                    need(a, 2);
                    if (a.length > 2 && a[2].equalsIgnoreCase("clear")) {
                        edit(p, a, 2, y -> y.set("fortress", null), "Крепость убрана");
                        return;
                    }
                    Pos[] sel = selection(p);
                    if (sel == null) return;
                    ru.mgp.util.Region r = ru.mgp.util.Region.of(sel[0], sel[1]);
                    edit(p, a, 2, y -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("min", r.min().serialize());
                        m.put("max", r.max().serialize());
                        addMap(y, "fortress", m);
                    }, "Область крепости добавлена (" + (r.maxX() - r.minX() + 1) + "×" + (r.maxY() - r.minY() + 1) + "×" + (r.maxZ() - r.minZ() + 1)
                            + "). Ещё область — выдели и введи команду снова; убрать всё — /mgp setup fortress " + a[1] + " clear");
                }
                case "objectives" -> listObjectives(p, a);
                case "tpspawn" -> {
                    need(a, 3);
                    MapConfig m = plugin.registry().map(a[1]);
                    if (m == null) { Msg.send(p, "<red>" + Msg.esc(plugin.registry().mapNotFound(a[1]))); return; }
                    if (a[2].startsWith("p")) {
                        int k;
                        try { k = Integer.parseInt(a[2].substring(1)); } catch (NumberFormatException e) { k = -1; }
                        if (k < 1 || k > m.spawnPoints.size()) { Msg.send(p, "<red>Нет спавна " + Msg.esc(a[2])); return; }
                        p.teleport(m.spawnPoints.get(k - 1).pos().toLocation(mapWorld(p, m)));
                        return;
                    }
                    int n;
                    try { n = Integer.parseInt(a[2]); } catch (NumberFormatException e) { n = -1; }
                    if (n < 1 || n > m.spawns.size()) { Msg.send(p, "<red>Нет спавна №" + Msg.esc(a[2]) + " (всего " + m.spawns.size() + ")"); return; }
                    p.teleport(m.spawns.get(n - 1).toLocation(mapWorld(p, m)));
                    Msg.send(p, "<green>Спавн №" + n + " карты " + m.id);
                }
                case "clearspawns" -> edit(p, a, 2, y -> y.set("spawns", new ArrayList<String>()), "Спавны очищены");
                case "spectator" -> edit(p, a, 2, y -> y.set("spectator", Pos.of(loc).serialize()), "Точка наблюдателей задана");
                case "seekerspawn" -> edit(p, a, 2, y -> {
                    if (a.length > 2 && a[2].equalsIgnoreCase("none")) y.set("seeker-spawn", null);
                    else y.set("seeker-spawn", Pos.of(loc).serialize());
                }, "Комната ищущих задана (none — убрать)");
                case "modes" -> {
                    // /mgp setup modes <карта> — меню; /mgp setup modes <карта> <режим> [режим...] — задать список (первый — основной)
                    need(a, 2);
                    MapConfig m = plugin.registry().map(a[1]);
                    if (m == null) { Msg.send(p, "<red>" + Msg.esc(plugin.registry().mapNotFound(a[1]))); return; }
                    if (a.length == 2) { new MapModesMenu(plugin, m).open(p); return; }
                    List<String> list = new ArrayList<>();
                    for (int i = 2; i < a.length; i++) {
                        if (plugin.registry().mode(a[i]) == null) { Msg.send(p, "<red>Нет режима " + Msg.esc(a[i]) + ". Есть: " + String.join(", ", plugin.registry().modes().stream().map(x -> x.id).toList())); return; }
                        list.add(a[i]);
                    }
                    MapModesMenu.apply(plugin, p, m, list);
                }
                case "maps" -> {
                    // /mgp setup maps [режим] — какие карты в каком режиме
                    for (ModeConfig mc : plugin.registry().modes()) {
                        if (a.length > 1 && !mc.id.equalsIgnoreCase(a[1])) continue;
                        List<MapConfig> ms = plugin.registry().mapsFor(mc.id);
                        StringBuilder sb = new StringBuilder(mc.displayName + " <dark_gray>(" + mc.id + ")<gray>: ");
                        if (ms.isEmpty()) sb.append("<red>нет карт");
                        for (int i = 0; i < ms.size(); i++) {
                            MapConfig mm = ms.get(i);
                            if (i > 0) sb.append("<gray>, ");
                            sb.append("<click:run_command:'/mgp setup modes ").append(mm.id).append("'><hover:show_text:'Режимы карты'>")
                                    .append(plugin.sessions().playable(mm) ? "<white>" : "<red>").append(mm.id).append("</hover></click>");
                        }
                        p.sendMessage(Msg.parse(sb.toString()));
                    }
                    Msg.send(p, "<gray>Клик по карте — выбрать её режимы. Красным — карта без шаблона или спавнов.");
                }
                case "alsomode" -> {
                    need(a, 3);
                    String md = a[2].toLowerCase(Locale.ROOT);
                    boolean remove = md.startsWith("-");
                    String id = remove ? md.substring(1) : md;
                    if (!remove && plugin.registry().mode(id) == null) { Msg.send(p, "<red>Нет режима " + id); return; }
                    edit(p, a, 3, y -> {
                        List<String> l = new ArrayList<>(y.getStringList("also-modes"));
                        l.remove(id);
                        if (!remove) l.add(id);
                        y.set("also-modes", l.isEmpty() ? null : l);
                    }, remove ? "Режим " + id + " убран у карты" : "Карта теперь играется и в режиме " + id);
                }
                case "center" -> edit(p, a, 2, y -> {
                    y.set("center", Pos.ofBlock(loc).serialize());
                    if (a.length > 2) y.set("border-size", Double.parseDouble(a[2]));
                }, "Центр задан");
                case "chest" -> {
                    need(a, 3);
                    Block b = p.getTargetBlockExact(6);
                    if (b == null) { Msg.send(p, "<red>Посмотри на сундук."); return; }
                    edit(p, a, 3, y -> addString(y, "chests", b.getX() + " " + b.getY() + " " + b.getZ() + " " + a[2]),
                            "Сундук " + a[2] + " добавлен (" + b.getType() + ")");
                }
                case "trader" -> edit(p, a, 3, y -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("pos", Pos.of(loc).serialize());
                    m.put("set", a[2]);
                    m.put("name", "<yellow>Торговец");
                    m.put("profession", a.length > 3 ? a[3] : "librarian");
                    addMap(y, "traders", m);
                }, "Торговец добавлен");
                case "pos1", "pos2" -> setPos(p, sub.equals("pos1"), Pos.ofBlock(loc));
                case "expand", "contract", "shift", "outset", "inset", "face", "box", "size", "sel", "selection", "faces" ->
                        selOps.handle(p, Arrays.copyOfRange(a, 1, a.length));
                case "clearsel" -> {
                    pos1.remove(p.getUniqueId()); pos2.remove(p.getUniqueId()); posWorld.remove(p.getUniqueId());
                    display.remove(p.getUniqueId());
                    Msg.send(p, "<green>Выделение убрано.");
                }
                case "optimize" -> optimizeMap(p, a);
                case "rename" -> renameMap(p, a);
                case "name" -> {
                    need(a, 3);
                    String nm = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
                    edit(p, a, 3, y -> y.set("display-name", nm), "Название карты для игроков: ");
                    MapConfig upd = plugin.registry().map(a[1]);
                    if (upd != null) p.sendMessage(Msg.parse(upd.displayName, Map.of()));
                }
                case "biome" -> {
                    need(a, 3);
                    String b = a[2].toLowerCase(Locale.ROOT);
                    boolean none = b.equals("none") || b.equals("off");
                    if (!none && ru.mgp.world.WorldService.biome(b) == null) { Msg.send(p, "<red>Нет биома " + Msg.esc(a[2]) + ". Подсказки — по Tab."); return; }
                    edit(p, a, 3, y -> y.set("biome", none ? null : b),
                            none ? "Биом карты убран — будет как в шаблоне" : "Биом карты: " + b + " (со следующей игры)");
                }
                case "plot" -> {
                    Pos[] sel = selection(p);
                    if (sel == null) return;
                    edit(p, a, 2, y -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("min", sel[0].serialize());
                        m.put("max", sel[1].serialize());
                        m.put("spawn", Pos.of(loc).serialize());
                        addMap(y, "plots", m);
                    }, "Участок добавлен");
                }
                case "trigger" -> {
                    need(a, 3);
                    Pos[] sel = selection(p);
                    if (sel == null) return;
                    edit(p, a, 3, y -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("name", a[2]);
                        m.put("min", sel[0].serialize());
                        m.put("max", sel[1].serialize());
                        m.put("mode", a.length > 3 ? a[3].toUpperCase(Locale.ROOT) : "ONCE");
                        m.put("actions", List.of("message: <yellow>Сработал триггер " + a[2]));
                        addMap(y, "triggers", m);
                    }, "Триггер добавлен — впиши действия в файл карты и сделай /mgp reload");
                }
                case "lobby" -> setupLobby(p, a);
                case "script" -> setupScript(p, a);
                case "scripts" -> {
                    need(a, 2);
                    MapConfig m = plugin.registry().map(a[1]);
                    if (m == null) { Msg.send(p, "<red>" + Msg.esc(plugin.registry().mapNotFound(a[1]))); return; }
                    if (m.scripts.isEmpty()) Msg.send(p, "<gray>На карте нет скриптов. /mgp setup script <карта> <id> hold|button|plate|zone");
                    for (MapConfig.Script sc : m.scripts)
                        Msg.send(p, "<gold>" + Msg.esc(sc.id()) + "</gold> <gray>" + sc.type().name().toLowerCase(Locale.ROOT) + " у " + sc.block().serialize()
                                + ", действий: " + sc.actions().size() + (sc.type() == MapConfig.Script.Type.HOLD ? ", держать " + sc.hold() + " с" : ""));
                }
                case "waiting" -> {
                    need(a, 2);
                    ModeConfig m = plugin.registry().mode(a[1]);
                    if (m == null) { Msg.send(p, "<red>Нет режима " + a[1]); return; }
                    m.yaml.set("waiting.spawn", Pos.of(loc).serialize());
                    save(m.yaml, m.file);
                    plugin.registry().reloadMode(m.id);
                    Msg.send(p, "<green>Точка ожидания для " + m.id + " задана.");
                }
                case "info" -> {
                    need(a, 2);
                    MapConfig m = plugin.registry().map(a[1]);
                    if (m == null) { Msg.send(p, "<red>" + Msg.esc(plugin.registry().mapNotFound(a[1]))); return; }
                    Msg.send(p, "<gold>" + m.id + "</gold> режимы=" + m.mode + (m.alsoModes.isEmpty() ? "" : ", " + String.join(", ", m.alsoModes)) + " шаблон=" + m.template
                            + (plugin.worlds().templateExists(m.template) ? " <green>✔" : " <red>(шаблона нет!)"));
                    Msg.send(p, "<gray>спавнов " + m.spawns.size() + ", сундуков " + m.chests.size() + ", торговцев "
                            + m.traders.size() + ", триггеров " + m.triggers.size() + ", участков " + m.plots.size());
                    listSpawns(p, a);
                }
                default -> setup(p, new String[0]);
            }
        } catch (IllegalArgumentException e) {
            Msg.send(p, "<red>" + Msg.esc(String.valueOf(e.getMessage())));
        }
    }

    // ------------------------------------------------------------------ import

    /** /mgp import <карта> <режим> [x1 y1 z1 x2 y2 z2] [мир] [название...] */
    private void importMap(CommandSender sender, String[] a) {
        if (a.length < 3) {
            Msg.send(sender, "<red>/mgp import <карта> <режим> <x1> <y1> <z1> <x2> <y2> <z2> [мир] [название]");
            Msg.send(sender, "<gray>или выдели область /mgp setup pos1 и pos2 и введи /mgp import <карта> <режим>");
            return;
        }
        String mapId = ConfigRegistry.cleanId(a[1]), modeId = ConfigRegistry.cleanId(a[2]);
        Pos p1, p2;
        org.bukkit.World world;
        int rest;
        try {
            if (a.length >= 9) {
                p1 = new Pos(Double.parseDouble(a[3]), Double.parseDouble(a[4]), Double.parseDouble(a[5]), 0, 0);
                p2 = new Pos(Double.parseDouble(a[6]), Double.parseDouble(a[7]), Double.parseDouble(a[8]), 0, 0);
                if (a.length >= 10) world = org.bukkit.Bukkit.getWorld(a[9]);
                else world = sender instanceof Player pl ? pl.getWorld() : plugin.hub().world();
                rest = 10;
            } else {
                if (!(sender instanceof Player pl)) { Msg.send(sender, "<red>Укажи координаты."); return; }
                p1 = pos1.get(pl.getUniqueId());
                p2 = pos2.get(pl.getUniqueId());
                if (p1 == null || p2 == null) { Msg.send(sender, "<red>Сначала /mgp setup pos1 и pos2 или укажи координаты."); return; }
                world = org.bukkit.Bukkit.getWorld(posWorld.getOrDefault(pl.getUniqueId(), pl.getWorld().getName()));
                rest = 3;
            }
        } catch (NumberFormatException e) {
            Msg.send(sender, "<red>Координаты должны быть числами.");
            return;
        }
        if (world == null) { Msg.send(sender, "<red>Мир не найден."); return; }
        String name = a.length > rest ? String.join(" ", Arrays.copyOfRange(a, rest, a.length)) : null;
        Msg.send(sender, "<gray>Вырезаю карту из мира " + world.getName() + "...");
        boolean fromSelection = rest == 3;
        plugin.importer().run(new ru.mgp.world.MapImporter.Request(world, mapId, modeId, ru.mgp.util.Region.of(p1, p2), name, true,
                plugin.getConfig().getInt("import.margin-chunks", 1), false, optimizeBorders())).whenComplete((r, ex) -> {
            if (ex != null) { Msg.send(sender, "<red>Ошибка импорта: " + Msg.esc(String.valueOf(ex.getMessage()))); return; }
            Msg.send(sender, "<green>Готово: " + r.summary());
            // рамка выделения показывает итоговую (обрезанную) область
            if (fromSelection && r.trimmed() && sender instanceof Player pl) showBox(pl, r.box());
            Msg.send(sender, "<gray>Координаты в шаблоне те же, что в исходном мире: спавны ставь прямо там — /mgp setup spawn " + r.mapId());
        });
    }

    private interface YamlEdit { void apply(YamlConfiguration y); }

    private void edit(Player p, String[] a, int need, YamlEdit fn, String done) {
        need(a, need);
        MapConfig m = plugin.registry().map(a[1]);
        if (m == null) {
            Msg.send(p, "<red>" + Msg.esc(plugin.registry().mapNotFound(a[1])));
            Msg.send(p, "<gray>Новую карту: /mgp import или /mgp setup create");
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(m.file);
        fn.apply(y);
        save(y, m.file);
        plugin.registry().loadMap(m.file);
        Msg.send(p, "<green>" + done);
    }

    /** /mgp setup lobby <режим>: область pos1/pos2 -> templates/<шаблон лобби>, waiting.spawn = позиция игрока. */
    private void setupLobby(Player p, String[] a) {
        need(a, 2);
        if (a[1].equalsIgnoreCase("default")) { setupDefaultLobby(p); return; }
        ModeConfig mode = plugin.registry().mode(a[1]);
        if (mode == null) { Msg.send(p, "<red>Нет режима " + Msg.esc(a[1])); return; }
        Pos[] sel = selection(p);
        if (sel == null) return;
        String selWorld = posWorld.get(p.getUniqueId());
        if (selWorld != null && !selWorld.equals(p.getWorld().getName())) { Msg.send(p, "<red>pos1/pos2 заданы в другом мире."); return; }
        ru.mgp.util.Region box = ru.mgp.util.Region.of(sel[0], sel[1]);
        Location loc = p.getLocation();
        if (!box.contains(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())) {
            Msg.send(p, "<red>Встань внутри выделенной области — твоя позиция станет точкой появления в лобби.");
            return;
        }
        String template = mode.waitingTemplate != null && mode.waitingTemplate.matches("[a-z0-9_]{1,32}") ? mode.waitingTemplate : mode.id + "_waiting";
        String spawn = Pos.of(loc).serialize();
        Msg.send(p, "<gray>Вырезаю лобби ожидания в templates/" + template + "...");
        plugin.importer().run(new ru.mgp.world.MapImporter.Request(p.getWorld(), template, mode.id, box, null, false, 0, true, optimizeBorders())).whenComplete((r, ex) -> {
            if (ex != null) { Msg.send(p, "<red>Ошибка: " + Msg.esc(String.valueOf(ex.getMessage()))); return; }
            ModeConfig m = plugin.registry().mode(mode.id);
            m.yaml.set("waiting.template", template);
            m.yaml.set("waiting.spawn", spawn);
            m.yaml.set("waiting.void-y", r.box().minY() - 5);
            save(m.yaml, m.file);
            plugin.registry().reloadMode(m.id);
            Msg.send(p, "<green>Лобби ожидания для " + m.id + " готово: " + r.chunks() + " чанков, спавн " + spawn + ". Новые игры уже будут в нём.");
        });
    }

    /** /mgp setup lobby default: общее лобби ожидания для всех режимов, у которых нет своего. */
    private void setupDefaultLobby(Player p) {
        Pos[] sel = selection(p);
        if (sel == null) return;
        String selWorld = posWorld.get(p.getUniqueId());
        if (selWorld != null && !selWorld.equals(p.getWorld().getName())) { Msg.send(p, "<red>pos1/pos2 заданы в другом мире."); return; }
        ru.mgp.util.Region box = ru.mgp.util.Region.of(sel[0], sel[1]);
        Location loc = p.getLocation();
        if (!box.contains(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())) {
            Msg.send(p, "<red>Встань внутри выделенной области — твоя позиция станет точкой появления в лобби.");
            return;
        }
        String template = plugin.getConfig().getString("default-lobby.template", "waiting_lobby");
        if (!template.matches("[a-z0-9_]{1,32}")) template = "waiting_lobby";
        String tpl = template, spawn = Pos.of(loc).serialize();
        Msg.send(p, "<gray>Вырезаю общее лобби ожидания в templates/" + tpl + "...");
        plugin.importer().run(new ru.mgp.world.MapImporter.Request(p.getWorld(), tpl, "default", box, null, false, 0, true, optimizeBorders())).whenComplete((r, ex) -> {
            if (ex != null) { Msg.send(p, "<red>Ошибка: " + Msg.esc(String.valueOf(ex.getMessage()))); return; }
            plugin.getConfig().set("default-lobby.template", tpl);
            plugin.getConfig().set("default-lobby.spawn", spawn);
            plugin.getConfig().set("default-lobby.void-y", r.box().minY() - 5);
            plugin.saveConfig();
            List<String> own = new ArrayList<>();
            for (ModeConfig m : plugin.registry().modes()) if (plugin.worlds().templateExists(m.waitingTemplate) && !m.waitingTemplate.equals(tpl)) own.add(m.id);
            Msg.send(p, "<green>Общее лобби готово: " + r.chunks() + " чанков, спавн " + spawn + ". Его получают все режимы без своего лобби.");
            if (!own.isEmpty()) Msg.send(p, "<gray>Со своим лобби (оно важнее общего): " + String.join(", ", own));
        });
    }

    private void setPos(Player p, boolean first, Pos pos) {
        UUID id = p.getUniqueId();
        String w = p.getWorld().getName();
        if (!w.equals(posWorld.get(id))) { pos1.remove(id); pos2.remove(id); } // старый угол в другом мире больше не нужен
        (first ? pos1 : pos2).put(id, pos);
        posWorld.put(id, w);
        Pos a = pos1.get(id), b = pos2.get(id);
        String size = a != null && b != null ? "  <gray>(" + (Math.abs(a.bx() - b.bx()) + 1) + "×" + (Math.abs(a.by() - b.by()) + 1)
                + "×" + (Math.abs(a.bz() - b.bz()) + 1) + ")" : "";
        Msg.send(p, (first ? "<red>■ <green>pos1 = " : "<blue>■ <green>pos2 = ") + pos.serialize() + size);
        display.update(p);
    }

    private void showBox(Player p, ru.mgp.util.Region b) {
        pos1.put(p.getUniqueId(), b.min());
        pos2.put(p.getUniqueId(), b.max());
        display.update(p);
        Msg.send(p, "<gray>Рамка выделения теперь показывает обрезанную область.");
    }

    /** /mgp setup optimize <карта>: пересоздать шаблон из исходной области с обрезкой пустых краёв. */
    private void optimizeMap(CommandSender p, String[] a) {
        need(a, 2);
        MapConfig m = plugin.registry().map(a[1]);
        if (m == null) { Msg.send(p, "<red>" + Msg.esc(plugin.registry().mapNotFound(a[1]))); return; }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(m.file);
        String wName = y.getString("source.world");
        Pos min = Pos.parse(y.getString("source.min")), max = Pos.parse(y.getString("source.max"));
        if (wName == null || min == null || max == null) {
            Msg.send(p, "<red>Карта " + m.id + " не импортировалась из мира (нет source в файле карты). Используй /mgp import с выделением.");
            return;
        }
        org.bukkit.World w = org.bukkit.Bukkit.getWorld(wName);
        if (w == null) { Msg.send(p, "<red>Исходный мир " + Msg.esc(wName) + " не загружен."); return; }
        Msg.send(p, "<gray>Ищу пустые края карты " + m.id + " и пересоздаю шаблон из мира " + w.getName() + "...");
        plugin.importer().run(new ru.mgp.world.MapImporter.Request(w, m.id, m.mode, ru.mgp.util.Region.of(min, max), null, true,
                plugin.getConfig().getInt("import.margin-chunks", 1), false, true)).whenComplete((r, ex) -> {
            if (ex != null) { Msg.send(p, "<red>Ошибка: " + Msg.esc(String.valueOf(ex.getMessage()))); return; }
            Msg.send(p, "<green>Готово: " + r.summary());
            if (!r.trimmed()) Msg.send(p, "<gray>Пустых краёв не нашлось — область и так плотная.");
            if (p instanceof Player pl && pl.getWorld().equals(w)) { posWorld.put(pl.getUniqueId(), w.getName()); showBox(pl, r.box()); }
        });
    }

    /** /mgp setup rename <карта> <новый id> [название...] */
    private void renameMap(CommandSender s, String[] a) {
        if (a.length < 3) { Msg.send(s, "<red>/mgp setup rename <карта> <новый id> [название]"); return; }
        String name = a.length > 3 ? String.join(" ", Arrays.copyOfRange(a, 3, a.length)) : null;
        try {
            Msg.send(s, "<green>" + Msg.esc(ru.mgp.world.MapRenamer.rename(plugin, a[1], a[2], name)));
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            Msg.send(s, "<red>" + Msg.esc(String.valueOf(e.getMessage())));
        }
    }

    private Pos[] selection(Player p) {
        Pos a = pos1.get(p.getUniqueId()), b = pos2.get(p.getUniqueId());
        if (a == null || b == null) { Msg.send(p, "<red>Сначала задай /mgp setup pos1 и pos2"); return null; }
        return new Pos[]{a, b};
    }

    // ------------------------------------------------------------------ spawns

    /** /mgp setup delspawn <карта> [номер|last]: без номера удаляет ближайший к игроку спавн. */
    private void deleteSpawn(Player p, String[] a) {
        need(a, 2);
        MapConfig m = plugin.registry().map(a[1]);
        if (m == null) { Msg.send(p, "<red>" + Msg.esc(plugin.registry().mapNotFound(a[1]))); return; }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(m.file);
        List<String> list = new ArrayList<>(y.getStringList("spawns"));
        if (list.isEmpty()) { Msg.send(p, "<red>У карты " + m.id + " нет спавнов."); return; }
        int idx;
        String how;
        if (a.length > 2 && a[2].equalsIgnoreCase("last")) {
            idx = list.size() - 1;
            how = "последний";
        } else if (a.length > 2) {
            try { idx = Integer.parseInt(a[2]) - 1; } catch (NumberFormatException e) { idx = -1; }
            if (idx < 0 || idx >= list.size()) { Msg.send(p, "<red>Номер от 1 до " + list.size() + " или last."); return; }
            how = "№" + (idx + 1);
        } else {
            Location l = p.getLocation();
            idx = -1;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < list.size(); i++) {
                Pos s = Pos.parse(list.get(i));
                if (s == null) continue;
                double d = sq(s.x() - l.getX()) + sq(s.y() - l.getY()) + sq(s.z() - l.getZ());
                if (d < best) { best = d; idx = i; }
            }
            if (idx < 0) { Msg.send(p, "<red>Не удалось прочитать спавны карты."); return; }
            double dist = Math.sqrt(best);
            if (dist > 10) {
                Msg.send(p, String.format(Locale.ROOT, "<red>Ближайший спавн (№%d) в %.0f блоках от тебя. Подойди к нему или укажи номер: /mgp setup delspawn %s %d",
                        idx + 1, dist, m.id, idx + 1));
                return;
            }
            how = String.format(Locale.ROOT, "№%d, %.1f бл. от тебя", idx + 1, dist);
        }
        String removed = list.remove(idx);
        y.set("spawns", list);
        save(y, m.file);
        plugin.registry().loadMap(m.file);
        Msg.send(p, "<green>Спавн удалён (" + how + "): <white>" + removed + "</white>. Осталось: " + list.size());
    }

    private void listSpawns(Player p, String[] a) {
        need(a, 2);
        MapConfig m = plugin.registry().map(a[1]);
        if (m == null) { Msg.send(p, "<red>" + Msg.esc(plugin.registry().mapNotFound(a[1]))); return; }
        if (m.spawns.isEmpty() && m.spawnPoints.isEmpty()) { Msg.send(p, "<gray>Спавнов нет. Встань на точку: /mgp setup spawn " + m.id); return; }
        Location l = p.getLocation();
        if (!m.spawnPoints.isEmpty()) {
            Msg.send(p, "<gold>Спавны команд и групп " + m.id + " (" + m.spawnPoints.size() + "):");
            for (int i = 0; i < m.spawnPoints.size(); i++) {
                MapConfig.SpawnPoint sp = m.spawnPoints.get(i);
                Pos s = sp.pos();
                double d = Math.sqrt(sq(s.x() - l.getX()) + sq(s.y() - l.getY()) + sq(s.z() - l.getZ()));
                p.sendMessage(Msg.parse(String.format(Locale.ROOT,
                        "<gray>p%d.</gray> <white>%s</white> <yellow>%s</yellow>%s%s <dark_gray>(%.0f бл.)</dark_gray> "
                                + "<aqua><click:run_command:'/mgp setup tpspawn %s p%d'><hover:show_text:'Телепорт'>[тп]</hover></click></aqua>",
                        i + 1, s.serialize(), sp.team() == null ? "все" : Msg.esc(sp.team()),
                        sp.group() == null ? "" : " <light_purple>группа " + Msg.esc(sp.group()),
                        sp.enabled() ? "" : " <red>закрыт", d, m.id, i + 1)));
            }
            if (m.spawns.isEmpty()) return;
        }
        Msg.send(p, "<gold>Спавны " + m.id + " (" + m.spawns.size() + "):");
        for (int i = 0; i < m.spawns.size(); i++) {
            Pos s = m.spawns.get(i);
            double d = Math.sqrt(sq(s.x() - l.getX()) + sq(s.y() - l.getY()) + sq(s.z() - l.getZ()));
            // свой телепорт: /tp из Essentials не понимает yaw/pitch и пишет «Игрок не найден»
            String tp = "/mgp setup tpspawn " + m.id + " " + (i + 1);
            String del = "/mgp setup delspawn " + m.id + " " + (i + 1);
            p.sendMessage(Msg.parse(String.format(Locale.ROOT,
                    "<gray>%d.</gray> <white>%s</white> <dark_gray>(%.0f бл.)</dark_gray> "
                            + "<aqua><click:run_command:'%s'><hover:show_text:'Телепорт'>[тп]</hover></click></aqua> "
                            + "<red><click:suggest_command:'%s'><hover:show_text:'Подставить команду удаления'>[удалить]</hover></click></red>",
                    i + 1, s.serialize(), d, tp, del)));
        }
    }

    private static double sq(double v) { return v * v; }

    /**
     * /mgp setup objective <карта> <id> [процент] [название...] — цель на разрушение из выделения pos1/pos2;
     * /mgp setup objective <карта> <id> action <действие...> | clear | remove
     */
    private void objective(Player p, String[] a) {
        if (a.length < 3) {
            Msg.send(p, "<red>/mgp setup objective <карта> <id> [процент] [название]");
            Msg.send(p, "<gray>Область — выделение pos1/pos2. Потом: <yellow>objective <карта> <id> action <действие></yellow>, clear, remove");
            return;
        }
        String id = a[2];
        String sub = a.length > 3 ? a[3].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "action" -> {
                need(a, 5);
                String act = String.join(" ", Arrays.copyOfRange(a, 4, a.length));
                edit(p, a, 3, y -> editObjective(y, id, m -> {
                    List<String> l = new ArrayList<>(Cfg.strings(m.get("actions")));
                    l.add(act);
                    m.put("actions", l);
                }), "Действие добавлено к цели " + id + ": " + Msg.esc(act));
            }
            case "clear" -> edit(p, a, 3, y -> editObjective(y, id, m -> m.put("actions", new ArrayList<String>())), "Действия цели " + id + " очищены");
            case "remove" -> edit(p, a, 3, y -> {
                List<Map<?, ?>> l = new ArrayList<>(y.getMapList("objectives"));
                l.removeIf(m -> id.equals(String.valueOf(m.get("id"))));
                y.set("objectives", l.isEmpty() ? null : l);
            }, "Цель " + id + " удалена");
            default -> {
                Pos[] sel = selection(p);
                if (sel == null) return;
                double pct;
                int nameFrom = 3;
                try { pct = Double.parseDouble(sub.replace("%", "")); nameFrom = 4; } catch (NumberFormatException e) { pct = 50; }
                double percent = Math.max(1, Math.min(100, pct));
                String name = a.length > nameFrom ? String.join(" ", Arrays.copyOfRange(a, nameFrom, a.length)) : null;
                ru.mgp.util.Region r = ru.mgp.util.Region.of(sel[0], sel[1]);
                long vol = (long) (r.maxX() - r.minX() + 1) * (r.maxY() - r.minY() + 1) * (r.maxZ() - r.minZ() + 1);
                if (vol > ru.mgp.session.Objectives.MAX_VOLUME) { Msg.send(p, "<red>Слишком большая область: " + vol + " блоков (максимум " + ru.mgp.session.Objectives.MAX_VOLUME + ")"); return; }
                int solid = 0;
                for (int x = r.minX(); x <= r.maxX(); x++) for (int yy = r.minY(); yy <= r.maxY(); yy++) for (int z = r.minZ(); z <= r.maxZ(); z++)
                    if (p.getWorld().getBlockAt(x, yy, z).getType().isSolid()) solid++;
                int solidCount = solid;
                edit(p, a, 3, y -> editObjective(y, id, m -> {
                    m.put("min", r.min().serialize());
                    m.put("max", r.max().serialize());
                    m.put("percent", percent);
                    if (name != null) m.put("name", name.contains("<") ? name : "<red>" + name);
                    else m.putIfAbsent("name", "<red>" + id);
                    m.putIfAbsent("show", true);
                    m.putIfAbsent("actions", new ArrayList<String>());
                }), "Цель " + id + ": разрушить " + Math.round(percent) + "% из " + solidCount + " блоков постройки");
                Msg.send(p, "<gray>Что произойдёт: <yellow>/mgp setup objective " + a[1] + " " + id + " action <действие></yellow>, например "
                        + "<white>spawns: enable stage2</white>, <white>title: <red>Бункер уничтожен!</white>");
            }
        }
    }

    /** Найти цель по id в objectives (или создать) и изменить. */
    private static void editObjective(YamlConfiguration y, String id, java.util.function.Consumer<Map<String, Object>> fn) {
        List<Map<String, Object>> out = new ArrayList<>();
        boolean found = false;
        for (Map<?, ?> raw : y.getMapList("objectives")) {
            Map<String, Object> m = new LinkedHashMap<>(Cfg.asMap(raw));
            if (id.equals(String.valueOf(m.get("id")))) { fn.accept(m); found = true; }
            out.add(m);
        }
        if (!found) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            fn.accept(m);
            if (!m.containsKey("min")) throw new IllegalArgumentException("Цели " + id + " нет — сначала создай её из выделения: /mgp setup objective <карта> " + id + " [процент]");
            out.add(m);
        }
        y.set("objectives", out);
    }

    private void listObjectives(Player p, String[] a) {
        need(a, 2);
        MapConfig m = plugin.registry().map(a[1]);
        if (m == null) { Msg.send(p, "<red>" + Msg.esc(plugin.registry().mapNotFound(a[1]))); return; }
        if (m.objectives.isEmpty()) { Msg.send(p, "<gray>Целей нет. Выдели постройку pos1/pos2 и введи /mgp setup objective " + m.id + " <id> <процент>"); return; }
        Msg.send(p, "<gold>Цели " + m.id + ":");
        for (MapConfig.Objective o : m.objectives) {
            p.sendMessage(Msg.parse("<gray>• <white>" + Msg.esc(o.id()) + "</white> " + o.name() + " <gray>— " + Math.round(o.percent()) + "%, "
                    + o.region().min().serialize() + " .. " + o.region().max().serialize()));
            for (String act : o.actions()) p.sendMessage(Msg.parse("<dark_gray>    → <gray>" + Msg.esc(act)));
        }
    }

    /** Мир, в котором заданы координаты карты: исходный мир импорта (source.world), если загружен, иначе мир игрока. */
    private static org.bukkit.World mapWorld(Player p, MapConfig m) {
        String wn = m.yaml.getString("source.world");
        org.bukkit.World w = wn == null ? null : org.bukkit.Bukkit.getWorld(wn);
        return w != null ? w : p.getWorld();
    }

    private static void need(String[] a, int n) {
        if (a.length < n) throw new IllegalArgumentException("Не хватает аргументов. /mgp setup — справка");
    }

    private static void addString(YamlConfiguration y, String path, String value) {
        List<String> list = new ArrayList<>(y.getStringList(path));
        list.add(value);
        y.set(path, list);
    }

    /** /mgp setup script <карта> <id> <hold|button|plate|zone|tphere|action|set|clear|remove> ... */
    private void setupScript(Player p, String[] a) {
        if (a.length < 4) {
            Msg.send(p, "<gold>Скрипты карты</gold> <gray>(вместо командных блоков, хранятся в maps/<карта>.yml → scripts)");
            Msg.send(p, "<yellow>/mgp setup script <карта> <id> hold</yellow> — смотри на блок (например, железный люк): игрок держит Shift рядом с ним");
            Msg.send(p, "<yellow>/mgp setup script <карта> <id> button|plate|zone</yellow> — ПКМ по блоку / наступить / войти в радиус");
            Msg.send(p, "<yellow>/mgp setup script <карта> <id> tphere</yellow> — добавить телепорт сюда (где ты стоишь)");
            Msg.send(p, "<yellow>/mgp setup script <карта> <id> action <действие></yellow> — например: action title: <red>Ловушка!");
            Msg.send(p, "<yellow>/mgp setup script <карта> <id> set <ключ> <значение></yellow> — hold, radius, hint, progress, particle, cooldown, mode (once|per_player|repeat), cancel-use");
            Msg.send(p, "<yellow>/mgp setup script <карта> <id> clear | remove</yellow> — убрать действия / удалить скрипт");
            return;
        }
        String id = a[2];
        String op = a[3].toLowerCase(Locale.ROOT);
        String rest = a.length > 4 ? String.join(" ", Arrays.copyOfRange(a, 4, a.length)) : "";
        switch (op) {
            case "hold", "button", "plate", "zone" -> {
                Block b = p.getTargetBlockExact(6);
                if (b == null || b.getType().isAir()) {
                    if (op.equals("hold") || op.equals("button")) { Msg.send(p, "<red>Посмотри на блок (до 6 блоков)."); return; }
                    b = p.getLocation().getBlock();
                }
                Block fb = b;
                editScript(p, a, id, true, m -> {
                    m.put("type", op);
                    m.put("block", fb.getX() + " " + fb.getY() + " " + fb.getZ());
                    if (op.equals("hold")) {
                        m.putIfAbsent("hold", 3);
                        m.putIfAbsent("hint", "<yellow>Зажми <gold><bold>SHIFT</bold></gold>, чтобы активировать");
                        m.putIfAbsent("progress", "<aqua>Телепорт");
                        m.putIfAbsent("particle", "PORTAL");
                    }
                    m.putIfAbsent("actions", new ArrayList<String>());
                }, "Скрипт " + id + " (" + op + ") на блоке " + b.getType() + " " + b.getX() + " " + b.getY() + " " + b.getZ()
                        + ". Теперь добавь действия: tphere или action <действие>");
            }
            case "tphere" -> editScript(p, a, id, false, m -> {
                List<String> acts = actions(m);
                acts.add("teleport: " + Pos.of(p.getLocation()).serialize());
                m.put("actions", acts);
            }, "Добавлен телепорт в " + Pos.of(p.getLocation()).serialize());
            case "action" -> {
                if (rest.isBlank()) { Msg.send(p, "<red>Впиши действие, например: action message: <green>Привет"); return; }
                editScript(p, a, id, false, m -> {
                    List<String> acts = actions(m);
                    acts.add(rest);
                    m.put("actions", acts);
                }, "Действие добавлено: " + Msg.esc(rest));
            }
            case "set" -> {
                if (a.length < 6) { Msg.send(p, "<red>/mgp setup script <карта> <id> set <ключ> <значение>"); return; }
                String k = a[4].toLowerCase(Locale.ROOT);
                String v = String.join(" ", Arrays.copyOfRange(a, 5, a.length));
                if (!List.of("hold", "radius", "hint", "progress", "particle", "cooldown", "mode", "cancel-use", "type", "block").contains(k)) {
                    Msg.send(p, "<red>Неизвестный ключ " + Msg.esc(k)); return;
                }
                editScript(p, a, id, false, m -> {
                    if (v.equalsIgnoreCase("none")) m.remove(k);
                    else if (k.equals("hold") || k.equals("radius")) m.put(k, Double.parseDouble(v.replace(',', '.')));
                    else if (k.equals("cooldown")) m.put(k, Integer.parseInt(v));
                    else if (k.equals("cancel-use")) m.put(k, Boolean.parseBoolean(v));
                    else m.put(k, v);
                }, k + " = " + Msg.esc(v));
            }
            case "clear" -> editScript(p, a, id, false, m -> m.put("actions", new ArrayList<String>()), "Действия скрипта " + id + " очищены");
            case "remove" -> {
                MapConfig mc = plugin.registry().map(a[1]);
                if (mc == null) { Msg.send(p, "<red>" + Msg.esc(plugin.registry().mapNotFound(a[1]))); return; }
                YamlConfiguration y = YamlConfiguration.loadConfiguration(mc.file);
                List<Map<?, ?>> list = new ArrayList<>(y.getMapList("scripts"));
                boolean removed = list.removeIf(x -> id.equals(String.valueOf(x.get("id"))));
                y.set("scripts", list.isEmpty() ? null : list);
                save(y, mc.file);
                plugin.registry().loadMap(mc.file);
                Msg.send(p, removed ? "<green>Скрипт " + id + " удалён" : "<red>Нет скрипта " + id);
            }
            default -> Msg.send(p, "<red>Неизвестно: " + Msg.esc(op) + ". /mgp setup script — справка");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> actions(Map<String, Object> m) {
        Object o = m.get("actions");
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) out.add(String.valueOf(x));
        return out;
    }

    /** Найти скрипт id в файле карты (или создать, если create) и изменить его. */
    private void editScript(Player p, String[] a, String id, boolean create, java.util.function.Consumer<Map<String, Object>> fn, String done) {
        MapConfig mc = plugin.registry().map(a[1]);
        if (mc == null) { Msg.send(p, "<red>" + Msg.esc(plugin.registry().mapNotFound(a[1]))); return; }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(mc.file);
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, Object> target = null;
        for (Map<?, ?> raw : y.getMapList("scripts")) {
            Map<String, Object> m = new LinkedHashMap<>();
            raw.forEach((k, v) -> m.put(String.valueOf(k), v));
            if (id.equals(String.valueOf(m.get("id")))) target = m;
            list.add(m);
        }
        if (target == null) {
            if (!create) { Msg.send(p, "<red>Нет скрипта " + Msg.esc(id) + ". Создай: /mgp setup script " + a[1] + " " + id + " hold|button|plate|zone"); return; }
            target = new LinkedHashMap<>();
            target.put("id", id);
            list.add(target);
        }
        fn.accept(target);
        y.set("scripts", list);
        save(y, mc.file);
        plugin.registry().loadMap(mc.file);
        Msg.send(p, "<green>" + done);
    }

    private static void addMap(YamlConfiguration y, String path, Map<String, Object> value) {
        List<Object> list = new ArrayList<>(y.getMapList(path));
        list.add(value);
        y.set(path, list);
    }

    private void save(YamlConfiguration y, File f) {
        try {
            f.getParentFile().mkdirs();
            y.save(f);
        } catch (IOException e) {
            throw new IllegalArgumentException("Не удалось сохранить " + f.getName() + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ tab

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (name.equals("play") && a.length == 1) {
            plugin.registry().modes().forEach(m -> out.add(m.id));
        } else if (name.equals("mgp")) {
            if (a.length == 1) {
                out.addAll(List.of("join", "leave", "menu", "help"));
                if (s.hasPermission("mgp.admin")) out.addAll(List.of("start", "list", "reload", "setup", "panel", "import", "optimize", "rename", "sethub", "hotbar", "wand", "shop", "debug"));
            } else if (a.length == 2 && a[0].equalsIgnoreCase("hotbar")) {
                out.addAll(List.of("on", "off"));
            } else if (a.length == 2 && a[0].equalsIgnoreCase("shop")) {
                plugin.shops().all().forEach(x -> out.add(x.id));
            } else if (a.length == 2 && (a[0].equalsIgnoreCase("optimize") || a[0].equalsIgnoreCase("rename") || a[0].equalsIgnoreCase("wand"))) {
                plugin.registry().maps().forEach(m -> out.add(m.id));
            } else if (a.length == 3 && a[0].equalsIgnoreCase("import")) {
                plugin.registry().modes().forEach(m -> out.add(m.id));
            } else if (a.length == 10 && a[0].equalsIgnoreCase("import")) {
                org.bukkit.Bukkit.getWorlds().forEach(w -> out.add(w.getName()));
            } else if (a.length == 2 && a[0].equalsIgnoreCase("join")) {
                plugin.registry().modes().forEach(m -> out.add(m.id));
            } else if (a.length == 2 && a[0].equalsIgnoreCase("setup")) {
                out.addAll(List.of("export", "create", "spawn", "delspawn", "spawns", "clearspawns", "spectator", "seekerspawn", "alsomode", "center", "chest",
                        "trader", "modes", "maps", "teamspawn", "objective", "objectives", "fortress", "pos1", "pos2", "clearsel", "expand", "contract", "shift", "outset", "inset", "face", "box", "size", "sel", "optimize", "rename", "name", "biome", "plot", "trigger", "script", "scripts", "waiting", "lobby", "info"));
            } else if (a.length >= 3 && a[0].equalsIgnoreCase("setup") && List.of("expand", "contract", "shift", "outset", "inset", "face", "box").contains(a[1].toLowerCase(Locale.ROOT))) {
                String sub = a[1].toLowerCase(Locale.ROOT);
                if (sub.equals("face")) out.addAll(a.length == 3 ? List.of(SelectionOps.IDS) : a.length == 4 ? List.of("here", "+1", "-1", "+10", "-10") : List.of());
                else if (sub.equals("box")) { if (a.length <= 8) out.add("~"); }
                else if (sub.equals("outset") || sub.equals("inset")) out.addAll(a.length == 3 ? List.of("1", "5", "10") : a.length == 4 ? List.of("-h", "-v") : List.of());
                else if (a.length == 3) out.addAll(sub.equals("expand") ? List.of("1", "5", "10", "vert") : List.of("1", "5", "10"));
                else if (a.length <= 5) out.addAll(SelectionOps.DIRECTIONS);
            } else if (a.length == 3 && a[0].equalsIgnoreCase("setup")) {
                if (a[1].equalsIgnoreCase("waiting") || a[1].equalsIgnoreCase("lobby")) plugin.registry().modes().forEach(m -> out.add(m.id));
                if (a[1].equalsIgnoreCase("lobby")) out.add("default");
                else if (!a[1].equalsIgnoreCase("export") && !a[1].equalsIgnoreCase("create"))
                    plugin.registry().maps().forEach(m -> out.add(m.id));
            } else if (a.length == 4 && a[0].equalsIgnoreCase("setup") && a[1].equalsIgnoreCase("script")) {
                MapConfig mc = plugin.registry().map(a[2]);
                if (mc != null) mc.scripts.forEach(x -> out.add(x.id()));
            } else if (a.length == 5 && a[0].equalsIgnoreCase("setup") && a[1].equalsIgnoreCase("script")) {
                out.addAll(List.of("hold", "button", "plate", "zone", "tphere", "action", "set", "clear", "remove"));
            } else if (a.length == 6 && a[0].equalsIgnoreCase("setup") && a[1].equalsIgnoreCase("script") && a[4].equalsIgnoreCase("set")) {
                out.addAll(List.of("hold", "radius", "hint", "progress", "particle", "cooldown", "mode", "cancel-use"));
            } else if (a.length >= 4 && a[0].equalsIgnoreCase("setup") && a[1].equalsIgnoreCase("modes")) {
                plugin.registry().modes().forEach(m -> out.add(m.id));
            } else if (a.length == 4 && a[0].equalsIgnoreCase("setup") && a[1].equalsIgnoreCase("teamspawn")) {
                out.addAll(List.of("all", "seeker", "hider", "defenders", "attackers"));
            } else if (a.length == 6 && a[0].equalsIgnoreCase("setup") && a[1].equalsIgnoreCase("teamspawn")) {
                out.add("locked");
            } else if (a.length == 5 && a[0].equalsIgnoreCase("setup") && a[1].equalsIgnoreCase("objective")) {
                out.addAll(List.of("50", "75", "100", "action", "clear", "remove"));
            } else if (a.length == 6 && a[0].equalsIgnoreCase("setup") && a[1].equalsIgnoreCase("objective") && a[4].equalsIgnoreCase("action")) {
                out.addAll(List.of("spawns:", "title:", "message:", "sound:", "mode:"));
            } else if (a.length == 4 && a[0].equalsIgnoreCase("setup") && a[1].equalsIgnoreCase("alsomode")) {
                plugin.registry().modes().forEach(m -> { out.add(m.id); out.add("-" + m.id); });
            } else if (a.length == 4 && a[0].equalsIgnoreCase("setup") && a[1].equalsIgnoreCase("biome")) {
                out.add("none");
                out.addAll(ru.mgp.world.WorldService.biomeIds());
            } else if (a.length == 4 && a[0].equalsIgnoreCase("setup") && a[1].equalsIgnoreCase("create")) {
                plugin.registry().modes().forEach(m -> out.add(m.id));
            } else if (a.length == 4 && a[0].equalsIgnoreCase("setup") && a[1].equalsIgnoreCase("delspawn")) {
                MapConfig m = plugin.registry().map(a[2]);
                out.add("last");
                if (m != null) for (int i = 1; i <= m.spawns.size(); i++) out.add(String.valueOf(i));
            } else if (a.length == 4 && a[0].equalsIgnoreCase("setup") && a[1].equalsIgnoreCase("chest")) {
                MapConfig m = plugin.registry().map(a[2]);
                ModeConfig mode = m == null ? null : plugin.registry().mode(m.mode);
                if (mode != null) out.addAll(mode.loot.keySet());
            }
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
