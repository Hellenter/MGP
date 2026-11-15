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

    public MgpCommand(MGPPlugin plugin) { this.plugin = plugin; }

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
            case "reload" -> {
                if (!admin(sender)) return true;
                plugin.reloadAll();
                Msg.send(sender, "<green>Конфиги перезагружены (идущие игры доиграют со старыми настройками).");
            }
            case "setup" -> { if (admin(sender)) setup(sender, Arrays.copyOfRange(a, 1, a.length)); }
            case "import" -> { if (admin(sender)) importMap(sender, a); }
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
            Msg.send(s, "<gold>/mgp setup</gold> — список команд настройки карт");
            Msg.send(s, "<gold>/mgp import <карта> <режим> <x1 y1 z1 x2 y2 z2> [мир] [название]</gold> — вырезать готовую карту из мира");
            Msg.send(s, "<gray>   без координат берётся выделение /mgp setup pos1 и pos2");
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
                    "<yellow>/mgp setup clearspawns <карта>",
                    "<yellow>/mgp setup spectator <карта></yellow> — точка наблюдателей",
                    "<yellow>/mgp setup center <карта> [размер барьера]",
                    "<yellow>/mgp setup chest <карта> <тир></yellow> — сундук, на который смотришь",
                    "<yellow>/mgp setup trader <карта> <набор сделок> [профессия]",
                    "<yellow>/mgp setup pos1 | pos2</yellow> — углы области",
                    "<yellow>/mgp setup plot <карта></yellow> — участок из pos1/pos2, спавн = ты",
                    "<yellow>/mgp setup trigger <карта> <имя> [once|per_player|repeat]",
                    "<yellow>/mgp setup waiting <режим></yellow> — точка в лобби ожидания",
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
                case "clearspawns" -> edit(p, a, 2, y -> y.set("spawns", new ArrayList<String>()), "Спавны очищены");
                case "spectator" -> edit(p, a, 2, y -> y.set("spectator", Pos.of(loc).serialize()), "Точка наблюдателей задана");
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
                case "pos1" -> { pos1.put(p.getUniqueId(), Pos.ofBlock(loc)); posWorld.put(p.getUniqueId(), p.getWorld().getName()); Msg.send(p, "<green>pos1 = " + Pos.ofBlock(loc).serialize()); }
                case "pos2" -> { pos2.put(p.getUniqueId(), Pos.ofBlock(loc)); posWorld.put(p.getUniqueId(), p.getWorld().getName()); Msg.send(p, "<green>pos2 = " + Pos.ofBlock(loc).serialize()); }
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
                    Msg.send(p, "<gold>" + m.id + "</gold> режим=" + m.mode + " шаблон=" + m.template
                            + (plugin.worlds().templateExists(m.template) ? " <green>✔" : " <red>(шаблона нет!)"));
                    Msg.send(p, "<gray>спавнов " + m.spawns.size() + ", сундуков " + m.chests.size() + ", торговцев "
                            + m.traders.size() + ", триггеров " + m.triggers.size() + ", участков " + m.plots.size());
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
        plugin.importer().run(new ru.mgp.world.MapImporter.Request(world, mapId, modeId, ru.mgp.util.Region.of(p1, p2), name, true,
                plugin.getConfig().getInt("import.margin-chunks", 0))).whenComplete((r, ex) -> {
            if (ex != null) { Msg.send(sender, "<red>Ошибка импорта: " + Msg.esc(String.valueOf(ex.getMessage()))); return; }
            Msg.send(sender, "<green>Готово: " + r.summary());
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

    private Pos[] selection(Player p) {
        Pos a = pos1.get(p.getUniqueId()), b = pos2.get(p.getUniqueId());
        if (a == null || b == null) { Msg.send(p, "<red>Сначала задай /mgp setup pos1 и pos2"); return null; }
        return new Pos[]{a, b};
    }

    private static void need(String[] a, int n) {
        if (a.length < n) throw new IllegalArgumentException("Не хватает аргументов. /mgp setup — справка");
    }

    private static void addString(YamlConfiguration y, String path, String value) {
        List<String> list = new ArrayList<>(y.getStringList(path));
        list.add(value);
        y.set(path, list);
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
                if (s.hasPermission("mgp.admin")) out.addAll(List.of("start", "list", "reload", "setup", "panel", "import"));
            } else if (a.length == 3 && a[0].equalsIgnoreCase("import")) {
                plugin.registry().modes().forEach(m -> out.add(m.id));
            } else if (a.length == 10 && a[0].equalsIgnoreCase("import")) {
                org.bukkit.Bukkit.getWorlds().forEach(w -> out.add(w.getName()));
            } else if (a.length == 2 && a[0].equalsIgnoreCase("join")) {
                plugin.registry().modes().forEach(m -> out.add(m.id));
            } else if (a.length == 2 && a[0].equalsIgnoreCase("setup")) {
                out.addAll(List.of("export", "create", "spawn", "clearspawns", "spectator", "center", "chest",
                        "trader", "pos1", "pos2", "plot", "trigger", "waiting", "info"));
            } else if (a.length == 3 && a[0].equalsIgnoreCase("setup")) {
                if (a[1].equalsIgnoreCase("waiting")) plugin.registry().modes().forEach(m -> out.add(m.id));
                else if (!a[1].equalsIgnoreCase("export") && !a[1].equalsIgnoreCase("create"))
                    plugin.registry().maps().forEach(m -> out.add(m.id));
            } else if (a.length == 4 && a[0].equalsIgnoreCase("setup") && a[1].equalsIgnoreCase("create")) {
                plugin.registry().modes().forEach(m -> out.add(m.id));
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
