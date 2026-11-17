package ru.mgp.command;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import ru.mgp.MGPPlugin;
import ru.mgp.config.MapConfig;
import ru.mgp.config.ModeConfig;
import ru.mgp.util.Items;
import ru.mgp.util.Msg;
import ru.mgp.util.Pos;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

/**
 * Набор строителя (/mgp tools): топор выделения, жезл точек, меню карты и палочка скриптов.
 * Жезл точек: ПКМ — поставить выбранную точку там, где стоишь (спавн, спавн команды или стадии, лимбо, наблюдатели, центр);
 * Shift+ПКМ / Shift+ЛКМ — следующая / предыдущая точка; ЛКМ — удалить ближайший спавн. Пока жезл в руке, спавны карты видны частицами.
 */
final class BuilderTools implements Listener {
    /** Точка, которую ставит жезл: подпись, значок, команда без «/» (координаты — где стоит игрок), цвет частиц. */
    record Target(String label, Material icon, String cmd, String team) {}

    private final MGPPlugin plugin;
    private final MgpCommand command;
    private final SelectionWand selWand;
    private final NamespacedKey pointKey, menuKey;
    private final Map<UUID, Integer> targetIndex = new HashMap<>();
    private final Map<UUID, Long> lastClick = new HashMap<>();
    private final Map<UUID, Consumer<String>> prompts = new HashMap<>();
    private int tick;

    BuilderTools(MGPPlugin plugin, MgpCommand command, SelectionWand selWand) {
        this.plugin = plugin;
        this.command = command;
        this.selWand = selWand;
        this.pointKey = new NamespacedKey(plugin, "point_tool");
        this.menuKey = new NamespacedKey(plugin, "map_menu_tool");
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 10L);
    }

    // ------------------------------------------------------------------ предметы

    void give(Player p) {
        List<ItemStack> want = new ArrayList<>();
        if (!has(p, selWand::isWandItem)) want.add(selWand.item());
        if (!has(p, it -> tagged(it, pointKey))) want.add(pointTool());
        if (!has(p, it -> tagged(it, menuKey))) want.add(menuTool());
        if (!has(p, plugin.wand()::isWand)) want.add(plugin.wand().item());
        for (ItemStack it : want) p.getInventory().addItem(it).values().forEach(left -> p.getWorld().dropItem(p.getLocation(), left));
        MapContext.Current c = plugin.mapContext().current(p);
        Msg.send(p, "<gold>Набор строителя:</gold> <gray><gold>топор</gold> — выделение (ЛКМ/ПКМ по блоку), <gold>жезл точек</gold> — спавны и точки "
                + "(ПКМ — поставить, Shift+ПКМ — сменить, ЛКМ — удалить ближайший), <gold>меню карты</gold> — всё остальное (ПКМ), <gold>палочка</gold> — скрипты.");
        Msg.send(p, c != null ? "<gray>Карта: <yellow>" + c.map().id + " <dark_gray>(" + c.how() + ")" : "<yellow>Карта не определена — встань в карту или /mgp map <карта>.");
    }

    private static boolean has(Player p, java.util.function.Predicate<ItemStack> test) {
        for (ItemStack it : p.getInventory().getContents()) if (it != null && test.test(it)) return true;
        return false;
    }

    private ItemStack tag(ItemStack it, NamespacedKey key) {
        var meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    private static boolean tagged(ItemStack it, NamespacedKey key) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    ItemStack pointTool() {
        return tag(Items.icon(Material.BLAZE_ROD, "<gold><bold>Жезл точек", List.of(
                "<green>ПКМ</green> <gray>— поставить точку там, где стоишь",
                "<yellow>Shift+ПКМ</yellow> <gray>/ <yellow>Shift+ЛКМ</yellow> <gray>— сменить точку",
                "<red>ЛКМ</red> <gray>— удалить ближайший спавн",
                "<dark_gray>Спавны карты видны частицами, пока жезл в руке"), true), pointKey);
    }

    ItemStack menuTool() {
        return tag(Items.icon(Material.RECOVERY_COMPASS, "<aqua><bold>Меню карты", List.of(
                "<green>ПКМ</green> <gray>— меню текущей карты",
                "<yellow>ЛКМ</yellow> <gray>— чек-лист: чего не хватает"), true), menuKey);
    }

    // ------------------------------------------------------------------ точки для режима карты

    static List<Target> targets(MGPPlugin plugin, MapConfig m) {
        List<Target> out = new ArrayList<>();
        Set<String> types = new LinkedHashSet<>();
        for (String id : MapCheck.modesOf(m)) {
            ModeConfig mc = plugin.registry().mode(id);
            types.add(mc == null ? id : mc.type);
        }
        String id = m.id;
        for (String type : types) {
            switch (type) {
                case "hunger_games" -> {
                    add(out, new Target("Спавн игрока", Material.WHITE_WOOL, "mgp setup spawn " + id, null));
                    add(out, new Target("Центр карты (барьер)", Material.BEACON, "mgp setup center " + id, "center"));
                }
                case "build_battle" -> add(out, new Target("Центр арены", Material.BEACON, "mgp setup arena " + id, "center"));
                case "hide_and_seek" -> {
                    add(out, new Target("Спавн прячущихся", Material.LIME_WOOL, "mgp setup spawn " + id, null));
                    add(out, new Target("Спавн ищущих", Material.ORANGE_WOOL, "mgp setup teamspawn " + id + " seeker", "seeker"));
                    add(out, new Target("Комната ищущих", Material.IRON_DOOR, "mgp setup seekerspawn " + id, "seeker"));
                }
                case "siege" -> {
                    add(out, new Target("Спавн защиты", Material.BLUE_WOOL, "mgp setup teamspawn " + id + " defenders", "defenders"));
                    add(out, new Target("Спавн атаки", Material.RED_WOOL, "mgp setup teamspawn " + id + " attackers", "attackers"));
                }
                case "assault" -> {
                    int stages = Math.max(1, m.yaml.getMapList("stages").size());
                    for (int n = 1; n <= stages; n++) {
                        add(out, new Target("Стадия " + n + ": спавн атаки", Material.RED_WOOL, "mgp setup assault " + id + " stage " + n + " spawn attackers", "attackers"));
                        add(out, new Target("Стадия " + n + ": спавн защиты", Material.BLUE_WOOL, "mgp setup assault " + id + " stage " + n + " spawn defenders", "defenders"));
                    }
                    add(out, new Target("Лимбо атаки", Material.RED_STAINED_GLASS, "mgp setup assault " + id + " limbo attackers", "attackers"));
                    add(out, new Target("Лимбо защиты", Material.BLUE_STAINED_GLASS, "mgp setup assault " + id + " limbo defenders", "defenders"));
                }
                default -> add(out, new Target("Спавн игрока", Material.WHITE_WOOL, "mgp setup spawn " + id, null));
            }
        }
        add(out, new Target("Точка наблюдателей", Material.ENDER_EYE, "mgp setup spectator " + id, "spectator"));
        return out;
    }

    private static void add(List<Target> l, Target t) {
        for (Target x : l) if (x.cmd().equals(t.cmd())) return;
        l.add(t);
    }

    private Target target(Player p, MapConfig m) {
        List<Target> l = targets(plugin, m);
        int i = Math.floorMod(targetIndex.getOrDefault(p.getUniqueId(), 0), l.size());
        return l.get(i);
    }

    // ------------------------------------------------------------------ клики

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        ItemStack it = e.getItem();
        boolean point = tagged(it, pointKey), menu = tagged(it, menuKey);
        if (!point && !menu) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (!p.hasPermission("mgp.admin")) return;
        long now = System.currentTimeMillis();
        Long last = lastClick.get(p.getUniqueId());
        if (last != null && now - last < 200) return; // в креативе ЛКМ иногда приходит дважды
        lastClick.put(p.getUniqueId(), now);
        boolean right = e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK;
        boolean left = e.getAction() == Action.LEFT_CLICK_AIR || e.getAction() == Action.LEFT_CLICK_BLOCK;
        if (!right && !left) return;
        MapConfig m = plugin.mapContext().map(p);
        if (m == null) { Msg.send(p, "<red>Не понял, какая тут карта.</red> <gray>Встань внутри карты или выбери: /mgp map <карта>"); return; }
        if (menu) {
            if (right) new SetupMenu(plugin, command, m, p).open(p);
            else MapCheck.print(plugin, p, m);
            return;
        }
        if (p.isSneaking()) {
            int d = right ? 1 : -1;
            targetIndex.merge(p.getUniqueId(), d, Integer::sum);
            Target t = target(p, m);
            p.sendActionBar(Msg.parse("<gold>Жезл точек: <yellow>" + t.label() + " <dark_gray>(Shift+ПКМ/ЛКМ — сменить)"));
            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.5f, 1.6f);
            return;
        }
        if (right) {
            Target t = target(p, m);
            p.performCommand(t.cmd());
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.4f);
        } else deleteNearest(p, m);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        ItemStack it = e.getPlayer().getInventory().getItemInMainHand();
        if (tagged(it, pointKey) || tagged(it, menuKey)) e.setCancelled(true);
    }

    /** ЛКМ жезлом: удалить ближайший к игроку спавн (обычный или командный) в радиусе 4 блоков. */
    private void deleteNearest(Player p, MapConfig m) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(m.file);
        Location l = p.getLocation();
        List<String> spawns = new ArrayList<>(y.getStringList("spawns"));
        List<Map<?, ?>> points = new ArrayList<>(y.getMapList("spawn-points"));
        int bestS = -1, bestP = -1;
        double best = 4 * 4;
        for (int i = 0; i < spawns.size(); i++) {
            double d = dist2(Pos.parse(spawns.get(i)), l);
            if (d < best) { best = d; bestS = i; bestP = -1; }
        }
        for (int i = 0; i < points.size(); i++) {
            Object pos = points.get(i).get("pos");
            double d = dist2(pos == null ? null : Pos.parse(String.valueOf(pos)), l);
            if (d < best) { best = d; bestP = i; bestS = -1; }
        }
        String what;
        if (bestS >= 0) { what = "спавн " + spawns.remove(bestS); y.set("spawns", spawns); }
        else if (bestP >= 0) {
            Map<?, ?> r = points.remove(bestP);
            what = "спавн " + (r.get("team") == null ? "" : r.get("team") + " ") + (r.get("group") == null ? "" : "(" + r.get("group") + ") ") + r.get("pos");
            y.set("spawn-points", points.isEmpty() ? null : points);
        } else {
            Msg.send(p, "<gray>Рядом (до 4 блоков) нет спавнов. Подойди к спавну и ЛКМ жезлом.");
            return;
        }
        try {
            y.save(m.file);
        } catch (IOException ex) {
            Msg.send(p, "<red>Не удалось сохранить " + m.file.getName() + ": " + Msg.esc(String.valueOf(ex.getMessage())));
            return;
        }
        plugin.registry().loadMap(m.file);
        Msg.send(p, "<yellow>Удалён " + Msg.esc(what) + " <dark_gray>[" + m.id + "]");
        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_ITEM_BREAK, 0.5f, 1.2f);
    }

    private static double dist2(Pos s, Location l) {
        if (s == null) return Double.MAX_VALUE;
        double dx = s.x() - l.getX(), dy = s.y() - l.getY(), dz = s.z() - l.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    // ------------------------------------------------------------------ ввод из чата для меню

    /** Следующее сообщение игрока в чат уйдёт в then (не в общий чат). «отмена» — отменить. */
    void prompt(Player p, String question, Consumer<String> then) {
        prompts.put(p.getUniqueId(), then);
        p.closeInventory();
        Msg.send(p, "<gold>» </gold>" + question + " <dark_gray>(напиши в чат; «отмена» — отменить)");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Consumer<String> c = prompts.remove(e.getPlayer().getUniqueId());
        if (c == null) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (text.isEmpty() || text.equalsIgnoreCase("отмена") || text.equalsIgnoreCase("cancel") || text.equals("-")) {
                p.sendActionBar(Msg.parse("<red>Отменено"));
                return;
            }
            c.accept(text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        prompts.remove(id);
        lastClick.remove(id);
    }

    // ------------------------------------------------------------------ подсказки в руке

    private void tick() {
        tick++;
        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            boolean point = tagged(hand, pointKey), menu = tagged(hand, menuKey);
            if (!point && !menu) continue;
            if (!p.hasPermission("mgp.admin")) continue;
            MapConfig m = plugin.mapContext().map(p);
            if (m == null) { p.sendActionBar(Msg.parse("<red>Карта не определена <gray>— встань в карту или /mgp map <карта>")); continue; }
            if (point) {
                Target t = target(p, m);
                p.sendActionBar(Msg.parse("<gray>" + m.id + " · <gold>ПКМ: <yellow>" + t.label() + " <dark_gray>· Shift+ПКМ — сменить · ЛКМ — удалить"));
                World w = MapContext.world(p, m);
                if (p.getWorld().equals(w)) showPoints(p, m);
            } else {
                String sel = command.selectionSize(p);
                p.sendActionBar(Msg.parse("<gray>" + m.id + " · <aqua>ПКМ — меню карты <dark_gray>· <aqua>ЛКМ — чек-лист"
                        + (sel != null ? " <dark_gray>· <gray>выделение " + sel : "")));
            }
        }
    }

    private void showPoints(Player p, MapConfig m) {
        Location me = p.getLocation();
        for (Pos s : m.spawns) column(p, me, s, Color.WHITE);
        for (MapConfig.SpawnPoint sp : m.spawnPoints)
            column(p, me, sp.pos(), !sp.enabled() ? Color.GRAY : teamColor(sp.team()));
        if (m.spectator != null) column(p, me, m.spectator, Color.PURPLE);
        if (m.center != null) column(p, me, new Pos(m.center.x() + 0.5, m.center.y() + 1, m.center.z() + 0.5, 0, 0), Color.YELLOW);
        if (m.arena != null) column(p, me, m.arena, Color.YELLOW);
        var lb = m.yaml.getConfigurationSection("limbo");
        if (lb != null) for (String team : lb.getKeys(false)) {
            Pos l = Pos.parse(lb.getString(team));
            if (l != null) column(p, me, l, teamColor(team));
        }
        Pos seeker = Pos.parse(m.yaml.getString("seeker-spawn"));
        if (seeker != null) column(p, me, seeker, Color.ORANGE);
    }

    private static Color teamColor(String team) {
        if (team == null) return Color.WHITE;
        return switch (team) {
            case "attackers" -> Color.RED;
            case "defenders" -> Color.BLUE;
            case "hider" -> Color.LIME;
            case "seeker" -> Color.ORANGE;
            default -> Color.AQUA;
        };
    }

    private void column(Player p, Location me, Pos s, Color c) {
        double dx = s.x() - me.getX(), dz = s.z() - me.getZ();
        if (dx * dx + dz * dz > 64 * 64) return;
        Particle.DustOptions dust = new Particle.DustOptions(c, 1.3f);
        for (double y = 0.1; y <= 2.1; y += 0.5)
            p.spawnParticle(Particle.DUST, s.x(), s.y() + y, s.z(), 1, 0, 0, 0, 0, dust);
        if (tick % 2 == 0) { // направление взгляда спавна
            double yaw = Math.toRadians(s.yaw());
            p.spawnParticle(Particle.DUST, s.x() - Math.sin(yaw) * 0.6, s.y() + 1.6, s.z() + Math.cos(yaw) * 0.6, 1, 0, 0, 0, 0, dust);
        }
    }

}
