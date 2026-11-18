package ru.mgp.editor;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import ru.mgp.MGPPlugin;
import ru.mgp.config.MapConfig;
import ru.mgp.session.GameSession;
import ru.mgp.util.Items;
import ru.mgp.util.Msg;
import ru.mgp.util.Pos;
import ru.mgp.util.Region;

import java.util.*;
import java.util.function.Consumer;

/**
 * Палочка скриптов: создание интерактивных объектов карты прямо в игре, без команд и командных блоков.
 * ЛКМ по блоку — выделить его и открыть меню скрипта (или создать новый). ПКМ — список скриптов карты.
 * Пока палочка в руке, все скрипты карты подсвечены (контур + подпись), точки телепорта отмечены.
 * Выбор точек (куда телепортировать, где поставить блок) — тоже палочкой: ЛКМ по блоку.
 */
public final class ScriptWand implements Listener {
    /** Что ждём от игрока после выбора в меню. */
    public enum PickKind { TELEPORT, BLOCK, MOVE }

    private record Pick(PickKind kind, Consumer<Location> done, String hint) {}

    private static final class State {
        String selected;          // id выделенного скрипта
        String selectedMap;
        Pick pick;
        Consumer<String> chat;
        String chatHint;
        long lastClick;
        String visualKey;
        final List<Entity> visuals = new ArrayList<>();
    }

    private final MGPPlugin plugin;
    private final NamespacedKey wandKey;
    private final Map<UUID, State> states = new HashMap<>();

    public ScriptWand(MGPPlugin plugin) {
        this.plugin = plugin;
        this.wandKey = new NamespacedKey(plugin, "script_wand");
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 10L);
    }

    public MGPPlugin plugin() { return plugin; }

    /** Выделение игрока (топор, pos1/pos2) в его текущем мире или null — подключает MgpCommand. */
    private java.util.function.Function<Player, Region> selection = p -> null;

    public void setSelectionSource(java.util.function.Function<Player, Region> f) { this.selection = f; }

    Region selection(Player p) { return selection.apply(p); }

    /**
     * Новый скрипт-область (type: area) на карте и сразу его редактор. id == null — area1, area2...
     * mode — REPEAT / ONCE / PER_PLAYER или null (REPEAT).
     */
    public void createArea(Player p, String mapId, Region r, String id, String mode) {
        String[] made = new String[1];
        boolean[] dup = {false};
        boolean ok = ScriptStore.edit(plugin, mapId, list -> {
            if (id != null && ScriptStore.find(list, id) != null) { dup[0] = true; return; }
            made[0] = id != null ? id : ScriptStore.nextId(list, "area");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", made[0]);
            m.put("type", "area");
            ScriptStore.putArea(m, r);
            m.put("on", "enter");
            if (mode != null) m.put("mode", mode.toUpperCase(Locale.ROOT).replace('-', '_'));
            m.put("actions", new ArrayList<String>());
            list.add(m);
        });
        if (dup[0]) { Msg.send(p, "<red>Скрипт " + Msg.esc(id) + " уже есть — открываю его."); openEditor(p, mapId, id); return; }
        if (!ok) { Msg.send(p, "<red>Не удалось сохранить карту " + Msg.esc(mapId)); return; }
        boolean has = false;
        for (ItemStack it : p.getInventory().getContents()) if (isWand(it)) has = true;
        if (!has) p.getInventory().addItem(item());
        Msg.send(p, "<green>Зона-триггер <white>" + made[0] + "</white> (" + ScriptStore.size(r) + ") создана. Добавь, что она делает: «Добавить действие».");
        openEditor(p, mapId, made[0]);
    }

    private State state(Player p) { return states.computeIfAbsent(p.getUniqueId(), k -> new State()); }

    // ------------------------------------------------------------------ предмет

    public ItemStack item() {
        ItemStack it = Items.icon(Material.BLAZE_ROD, "<gold><bold>Палочка скриптов", List.of(
                "<gray>ЛКМ по блоку — выделить и настроить скрипт",
                "<gray>(люк, кнопка, плита, любой блок)",
                "<gray>ПКМ — все скрипты карты",
                "<gray>Shift+ПКМ — отменить выбор точки",
                "",
                "<dark_gray>Скрипты подсвечены, пока палочка в руке"), true);
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    public boolean isWand(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    /** /mgp wand [карта] */
    public void give(Player p, String mapId) {
        State st = state(p);
        if (mapId != null) {
            MapConfig m = plugin.registry().map(mapId);
            if (m == null) { Msg.send(p, "<red>" + Msg.esc(plugin.registry().mapNotFound(mapId))); return; }
            plugin.mapContext().choose(p, m.id);
        }
        boolean has = false;
        for (ItemStack it : p.getInventory().getContents()) if (isWand(it)) has = true;
        if (!has) p.getInventory().addItem(item());
        MapConfig m = map(p);
        Msg.send(p, "<gold>Палочка скриптов</gold> <gray>— ЛКМ по блоку (например, по люку), чтобы сделать из него скрипт.");
        Msg.send(p, m != null ? "<gray>Карта: <yellow>" + Msg.esc(m.id) + " <dark_gray>(" + plugin.mapContext().current(p).how() + "; сменить — /mgp map <карта>)"
                : "<red>Не понял, какая тут карта.</red> <gray>Укажи её: /mgp wand <карта>");
    }

    // ------------------------------------------------------------------ какая карта

    /** Карта, которую сейчас редактирует игрок: закреплённая, мир игры или область, из которой карту импортировали. */
    public MapConfig map(Player p) {
        MapConfig m = plugin.mapContext() == null ? null : plugin.mapContext().map(p);
        return m;
    }

    // ------------------------------------------------------------------ события

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!isWand(e.getItem())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!p.hasPermission("mgp.admin")) { Msg.send(p, "<red>Палочка скриптов только для админов."); return; }
        State st = state(p);
        long now = System.currentTimeMillis();
        if (now - st.lastClick < 200) return; // в креативе ЛКМ иногда приходит дважды
        st.lastClick = now;
        Action a = e.getAction();
        Block b = e.getClickedBlock();
        // ЛКМ по далёкому блоку (дальше руки) — берём блок под прицелом, как //jumpto у WorldEdit
        if (a == Action.LEFT_CLICK_AIR) {
            Block far = p.getTargetBlockExact(96);
            if (far != null) { b = far; a = Action.LEFT_CLICK_BLOCK; }
        }

        if (st.pick != null) {
            Pick pk = st.pick;
            if ((a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) && p.isSneaking()) {
                st.pick = null;
                p.sendActionBar(Msg.parse("<red>Выбор точки отменён"));
                reopenEditor(p);
                return;
            }
            Location at;
            if (a == Action.LEFT_CLICK_BLOCK && b != null) {
                at = pk.kind() == PickKind.TELEPORT ? standOn(b, p) : b.getLocation();
                if (pk.kind() == PickKind.TELEPORT && !headroom(at)) Msg.send(p, "<yellow>Над этим блоком мало места — игрок может застрять. Проверь точку.");
            } else if ((a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) && pk.kind() == PickKind.TELEPORT) {
                Location l = p.getLocation();
                at = new Location(l.getWorld(), Math.round(l.getX() * 100) / 100.0, Math.round(l.getY() * 100) / 100.0, Math.round(l.getZ() * 100) / 100.0, Math.round(l.getYaw()), 0);
            } else {
                p.sendActionBar(Msg.parse(pk.hint()));
                return;
            }
            st.pick = null;
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.6f);
            markPoint(p, at, pk.kind() == PickKind.TELEPORT);
            pk.done().accept(at);
            return;
        }

        if (a == Action.LEFT_CLICK_BLOCK && b != null) {
            select(p, b);
        } else if (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) {
            MapConfig m = requireMap(p);
            if (m != null) new ScriptListMenu(this, p, m.id).open(p);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent e) {
        if (isWand(e.getPlayer().getInventory().getItemInMainHand())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        State st = states.get(e.getPlayer().getUniqueId());
        if (st == null || st.chat == null) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Consumer<String> c = st.chat;
            st.chat = null;
            if (c == null) return;
            if (text.equalsIgnoreCase("отмена") || text.equalsIgnoreCase("cancel") || text.equals("-")) {
                p.sendActionBar(Msg.parse("<red>Отменено"));
                reopenEditor(p);
                return;
            }
            c.accept(text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        State st = states.remove(e.getPlayer().getUniqueId());
        if (st != null) clearVisuals(st);
    }

    // ------------------------------------------------------------------ выделение

    private MapConfig requireMap(Player p) {
        MapConfig m = map(p);
        if (m == null) Msg.send(p, "<red>Не понял, какая это карта.</red> <gray>Встань в область карты или укажи её: /mgp wand <карта>");
        return m;
    }

    private void select(Player p, Block b) {
        MapConfig m = requireMap(p);
        if (m == null) return;
        List<Map<String, Object>> all = ScriptStore.cached(m);
        Map<String, Object> sc = ScriptStore.findAt(all, b.getX(), b.getY(), b.getZ());
        // клик по блоку внутри зоны-области (или по полу под ней) — её редактор; Shift+ЛКМ — новый скрипт на этом блоке
        if (sc == null && !p.isSneaking()) for (Map<String, Object> e : all) {
            Region r = "area".equalsIgnoreCase(ScriptStore.str(e, "type", "")) ? ScriptStore.area(e) : null;
            if (r != null && (r.contains(b.getX(), b.getY(), b.getZ()) || r.contains(b.getX(), b.getY() + 1, b.getZ()))) {
                sc = e;
                p.sendActionBar(Msg.parse("<gray>Это зона <white>" + Msg.esc(String.valueOf(e.get("id"))) + "</white>. Shift+ЛКМ — новый скрипт на блоке"));
                break;
            }
        }
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
        if (sc != null) {
            openEditor(p, m.id, String.valueOf(sc.get("id")));
        } else {
            new ScriptTypeMenu(this, p, m.id, b).open(p);
        }
    }

    public void openEditor(Player p, String mapId, String id) {
        State st = state(p);
        st.selected = id;
        st.selectedMap = mapId;
        st.visualKey = null;
        new ScriptEditorMenu(this, p, mapId, id).open(p);
    }

    public void reopenEditor(Player p) {
        State st = state(p);
        if (st.selected == null || st.selectedMap == null) return;
        MapConfig m = plugin.registry().map(st.selectedMap);
        if (m == null || ScriptStore.find(ScriptStore.load(m), st.selected) == null) return;
        openEditor(p, st.selectedMap, st.selected);
    }

    public void deselect(Player p) {
        State st = state(p);
        st.selected = null;
        st.visualKey = null;
    }

    // ------------------------------------------------------------------ ввод

    /** Ждать точку: ЛКМ по блоку (для телепорта — встать на блок), ПКМ — где стоишь (только телепорт). */
    public void pick(Player p, PickKind kind, String hint, Consumer<Location> done) {
        p.closeInventory();
        state(p).pick = new Pick(kind, done, hint);
        state(p).chat = null;
        Msg.send(p, hint);
        Msg.send(p, "<dark_gray>Палочка должна быть в руке. Shift+ПКМ — отмена.");
        p.sendActionBar(Msg.parse(hint));
    }

    /** Ждать строку в чате. «отмена» — отменить. */
    public void ask(Player p, String hint, Consumer<String> done) {
        p.closeInventory();
        State st = state(p);
        st.pick = null;
        st.chat = done;
        st.chatHint = hint;
        Msg.send(p, hint);
        Msg.send(p, "<dark_gray>Напиши в чат. «отмена» — отменить. Цвета: <red>...</red>, <bold>...</bold>");
    }

    // ------------------------------------------------------------------ точки

    /** Точка, где игрок встанет НА блок (учитывая высоту плит, ковров, люков), лицом туда же, куда смотрит админ. */
    public static Location standOn(Block b, Player looking) {
        double top = 0;
        var boxes = b.getCollisionShape().getBoundingBoxes();
        for (BoundingBox bb : boxes) top = Math.max(top, bb.getMaxY());
        if (boxes.isEmpty() && !b.isPassable()) top = 1;
        Location l = new Location(b.getWorld(), b.getX() + 0.5, b.getY() + top, b.getZ() + 0.5,
                Math.round(looking.getLocation().getYaw() / 45f) * 45f, 0);
        // если над блоком другой блок вплотную (например, кликнули по полу под низким потолком) — всё равно ставим на верх
        if (top >= 1.0 && top < 1.5 && l.getY() == Math.floor(l.getY())) l.setY(b.getY() + 1);
        return l;
    }

    static boolean headroom(Location feet) {
        Block f = feet.getBlock(), h = f.getRelative(0, 1, 0);
        double fy = feet.getY() - Math.floor(feet.getY());
        boolean feetOk = f.isPassable() || fy > 0.0 && f.getCollisionShape().getBoundingBoxes().stream().allMatch(bb -> bb.getMaxY() <= fy + 1e-6);
        return feetOk && h.isPassable() && (fy == 0 || f.getRelative(0, 2, 0).isPassable());
    }

    private void markPoint(Player p, Location at, boolean feet) {
        Location c = feet ? at.clone().add(0, 0.1, 0) : at.clone().add(0.5, 0.5, 0.5);
        for (int i = 0; i < 16; i++) {
            double ang = i * Math.PI / 8;
            p.spawnParticle(Particle.END_ROD, c.clone().add(Math.cos(ang) * 0.5, 0, Math.sin(ang) * 0.5), 1, 0, 0, 0, 0);
        }
    }

    // ------------------------------------------------------------------ подсветка скриптов

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            State st = states.get(p.getUniqueId());
            boolean holding = isWand(p.getInventory().getItemInMainHand());
            if (!holding) {
                if (st != null && !st.visuals.isEmpty()) clearVisuals(st);
                if (st != null && st.pick != null && p.getTicksLived() % 40 == 0) p.sendActionBar(Msg.parse("<yellow>Возьми палочку, чтобы выбрать точку"));
                continue;
            }
            st = state(p);
            if (st.pick != null) p.sendActionBar(Msg.parse(st.pick.hint()));
            MapConfig m = map(p);
            if (m == null) {
                clearVisuals(st);
                p.sendActionBar(Msg.parse("<red>Карта не найдена — /mgp wand <карта>"));
                continue;
            }
            List<Map<String, Object>> list = ScriptStore.cached(m);
            int loaded = 0;
            for (Map<String, Object> sc : list) {
                int[] b = ScriptStore.block(sc);
                if (b != null && p.getWorld().isChunkLoaded(b[0] >> 4, b[2] >> 4)) loaded++;
            }
            List<Map<?, ?>> traders = m.yaml.getMapList("traders");
            String key = p.getWorld().getName() + "|" + m.id + "|" + st.selected + "|" + list.hashCode() + "|" + traders.hashCode() + "|" + loaded;
            if (key.equals(st.visualKey) && st.visuals.stream().allMatch(Entity::isValid)) continue;
            clearVisuals(st);
            st.visualKey = key;
            for (Map<String, Object> sc : list) drawScript(p, st, sc, String.valueOf(sc.get("id")).equals(st.selected));
            for (Map<?, ?> t : traders) drawTrader(p, st, t);
        }
    }

    private void clearVisuals(State st) {
        for (Entity e : st.visuals) if (e.isValid()) e.remove();
        st.visuals.clear();
        st.visualKey = null;
    }

    static Color typeColor(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "hold" -> Color.fromRGB(80, 220, 255);
            case "plate" -> Color.fromRGB(90, 230, 90);
            case "zone" -> Color.fromRGB(200, 110, 255);
            case "area" -> Color.fromRGB(255, 120, 170);
            default -> Color.fromRGB(255, 210, 60);
        };
    }

    static String typeName(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "hold" -> "Удержание Shift";
            case "plate" -> "Наступить";
            case "zone" -> "Зона (радиус)";
            case "area" -> "Зона-область";
            default -> "ПКМ по блоку";
        };
    }

    private void drawScript(Player p, State st, Map<String, Object> sc, boolean selected) {
        int[] b = ScriptStore.block(sc);
        World w = p.getWorld();
        if (b == null || !w.isChunkLoaded(b[0] >> 4, b[2] >> 4)) return;
        String type = ScriptStore.str(sc, "type", "button");
        Color color = selected ? Color.WHITE : typeColor(type);
        Region area = "area".equalsIgnoreCase(type) ? ScriptStore.area(sc) : null;
        if (area != null) {
            // область: полупрозрачный светящийся параллелепипед на всю её величину
            float sx = area.maxX() - area.minX() + 1, sy = area.maxY() - area.minY() + 1, sz = area.maxZ() - area.minZ() + 1;
            BlockDisplay box = w.spawn(new Location(w, area.minX(), area.minY(), area.minZ()), BlockDisplay.class, x -> {
                x.setPersistent(false);
                x.setVisibleByDefault(false);
                x.setBlock((selected ? Material.WHITE_STAINED_GLASS : Material.PINK_STAINED_GLASS).createBlockData());
                x.setTransformation(new Transformation(new Vector3f(0.01f, 0.01f, 0.01f), new AxisAngle4f(), new Vector3f(sx - 0.02f, sy - 0.02f, sz - 0.02f), new AxisAngle4f()));
                x.setGlowing(true);
                x.setGlowColorOverride(color);
                x.setBrightness(new Display.Brightness(15, 15));
            });
            show(p, st, box);
            label(p, st, sc, new Location(w, (area.minX() + area.maxX() + 1) / 2.0 - 0.5, area.maxY() + 0.4, (area.minZ() + area.maxZ() + 1) / 2.0 - 0.5),
                    color, selected, type, ScriptStore.size(area) + whenText(sc));
            teleportMarks(p, st, sc, w, color);
            return;
        }
        Block block = w.getBlockAt(b[0], b[1], b[2]);
        boolean air = block.getType().isAir() || block.isPassable() && block.getCollisionShape().getBoundingBoxes().isEmpty() && !block.getType().isOccluding();
        Location base = new Location(w, b[0], b[1], b[2]);
        // контур: копия блока чуть больше оригинала, светится цветом типа (виден сквозь стены)
        BlockDisplay d = w.spawn(base, BlockDisplay.class, x -> {
            x.setPersistent(false);
            x.setVisibleByDefault(false);
            x.setBlock(air ? Material.LIGHT_BLUE_STAINED_GLASS.createBlockData() : block.getBlockData());
            float s = air ? 0.4f : 1.004f, off = air ? 0.3f : -0.002f;
            x.setTransformation(new Transformation(new Vector3f(off, off, off), new AxisAngle4f(), new Vector3f(s, s, s), new AxisAngle4f()));
            x.setGlowing(true);
            x.setGlowColorOverride(color);
            x.setBrightness(new Display.Brightness(15, 15));
        });
        show(p, st, d);
        String extra = "hold".equalsIgnoreCase(type) ? fmt(ScriptStore.num(sc, "hold", 3)) + " с" : "zone".equalsIgnoreCase(type) ? whenText(sc).trim() : "";
        label(p, st, sc, base, color, selected, type, extra);
        teleportMarks(p, st, sc, w, color);
    }

    /** «, пока внутри», «, при выходе», «, от 2 игроков» — для подписи зоны. */
    private static String whenText(Map<String, Object> sc) {
        String on = ScriptStore.str(sc, "on", "enter").toLowerCase(Locale.ROOT);
        int n = (int) ScriptStore.num(sc, "players", 1);
        return (on.equals("inside") ? ", пока внутри" : on.equals("leave") ? ", при выходе" : "") + (n > 1 ? ", от " + n + " игроков" : "");
    }

    private void label(Player p, State st, Map<String, Object> sc, Location base, Color color, boolean selected, String type, String extra) {
        World w = base.getWorld();
        List<String> acts = ScriptStore.actions(sc);
        String head = (selected ? "<white><bold>" : "<" + hex(color) + "><bold>") + Msg.esc(String.valueOf(sc.get("id"))) + "</bold>";
        String info = "<gray>" + typeName(type) + (extra == null || extra.isBlank() ? "" : " " + extra);
        String what = acts.isEmpty() ? "<red>нет действий" : "<white>" + ActionInfo.shortText(acts.get(0)) + (acts.size() > 1 ? " <gray>+" + (acts.size() - 1) : "");
        TextDisplay t = w.spawn(base.clone().add(0.5, 1.35, 0.5), TextDisplay.class, x -> {
            x.setPersistent(false);
            x.setVisibleByDefault(false);
            x.text(Msg.parse(head + "\n" + info + "\n" + what));
            x.setBillboard(Display.Billboard.CENTER);
            x.setSeeThrough(true);
            x.setShadowed(true);
            x.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.7f, 0.7f, 0.7f), new AxisAngle4f()));
        });
        show(p, st, t);
    }

    /** Точки телепорта из действий скрипта: площадка и подпись «сюда телепорт». */
    private void teleportMarks(Player p, State st, Map<String, Object> sc, World w, Color color) {
        for (String a : ScriptStore.actions(sc)) {
            Pos to = ActionInfo.teleportTarget(a);
            if (to == null || !w.isChunkLoaded((int) Math.floor(to.x()) >> 4, (int) Math.floor(to.z()) >> 4)) continue;
            Location l = to.toLocation(w);
            BlockDisplay pad = w.spawn(new Location(w, l.getX() - 0.35, l.getY() + 0.01, l.getZ() - 0.35), BlockDisplay.class, x -> {
                x.setPersistent(false);
                x.setVisibleByDefault(false);
                x.setBlock(Material.WHITE_CONCRETE.createBlockData());
                x.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.7f, 0.04f, 0.7f), new AxisAngle4f()));
                x.setGlowing(true);
                x.setGlowColorOverride(color);
            });
            show(p, st, pad);
            TextDisplay lt = w.spawn(l.clone().add(0, 2.2, 0), TextDisplay.class, x -> {
                x.setPersistent(false);
                x.setVisibleByDefault(false);
                x.text(Msg.parse("<" + hex(color) + ">▼ " + Msg.esc(String.valueOf(sc.get("id"))) + "\n<gray>сюда телепорт"));
                x.setBillboard(Display.Billboard.CENTER);
                x.setSeeThrough(true);
                x.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.6f, 0.6f, 0.6f), new AxisAngle4f()));
            });
            show(p, st, lt);
        }
    }

    /** Продавец на карте: светящаяся метка на месте появления и подпись. */
    private void drawTrader(Player p, State st, Map<?, ?> t) {
        Pos pos;
        try { pos = Pos.parse(String.valueOf(t.get("pos"))); } catch (Exception e) { return; }
        World w = p.getWorld();
        if (pos == null || !w.isChunkLoaded((int) Math.floor(pos.x()) >> 4, (int) Math.floor(pos.z()) >> 4)) return;
        Location l = pos.toLocation(w);
        Color c = Color.fromRGB(80, 255, 140);
        BlockDisplay pad = w.spawn(new Location(w, l.getX() - 0.3, l.getY(), l.getZ() - 0.3), BlockDisplay.class, x -> {
            x.setPersistent(false);
            x.setVisibleByDefault(false);
            x.setBlock(Material.EMERALD_BLOCK.createBlockData());
            x.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.6f, 1.9f, 0.6f), new AxisAngle4f()));
            x.setGlowing(true);
            x.setGlowColorOverride(c);
        });
        show(p, st, pad);
        Object shop = t.get("shop");
        String text = (t.get("name") == null ? "<yellow>Продавец" : String.valueOf(t.get("name"))) + "\n<gray>"
                + (shop != null ? "магазин " + Msg.esc(String.valueOf(shop)) : "сделки " + Msg.esc(String.valueOf(t.get("set"))));
        TextDisplay lt = w.spawn(l.clone().add(0, 2.4, 0), TextDisplay.class, x -> {
            x.setPersistent(false);
            x.setVisibleByDefault(false);
            x.text(Msg.parse(text));
            x.setBillboard(Display.Billboard.CENTER);
            x.setSeeThrough(true);
            x.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.7f, 0.7f, 0.7f), new AxisAngle4f()));
        });
        show(p, st, lt);
    }

    private void show(Player p, State st, Entity e) {
        p.showEntity(plugin, e);
        st.visuals.add(e);
    }

    static String hex(Color c) { return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue()); }

    static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }

    public void shutdown() {
        for (State st : states.values()) clearVisuals(st);
    }
}
