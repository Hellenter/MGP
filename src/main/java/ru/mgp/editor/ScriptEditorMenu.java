package ru.mgp.editor;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import ru.mgp.config.MapConfig;
import ru.mgp.ui.Menu;
import ru.mgp.util.Items;
import ru.mgp.util.Msg;
import ru.mgp.util.Region;

import java.util.*;
import java.util.function.Consumer;

/**
 * Меню скрипта: как срабатывает, параметры и список действий.
 * Каждое изменение сразу сохраняется в файл карты и меню открывается заново.
 */
final class ScriptEditorMenu extends Menu {
    static final List<String> PARTICLES = List.of("PORTAL", "REVERSE_PORTAL", "END_ROD", "WITCH", "ENCHANT", "SOUL_FIRE_FLAME",
            "FLAME", "HAPPY_VILLAGER", "ELECTRIC_SPARK", "CHERRY_LEAVES", "none");
    private static final List<String> TYPES = List.of("hold", "button", "plate", "zone");
    private static final List<String> ONS = List.of("enter", "inside", "leave");
    private static final List<String> MODES = List.of("REPEAT", "ONCE", "PER_PLAYER");
    private static final int FIRST_ACTION = 18, ACTION_SLOTS = 27;

    private final ScriptWand wand;
    private final String mapId, id;

    ScriptEditorMenu(ScriptWand wand, Player p, String mapId, String id) {
        super(6, "<dark_gray>Скрипт: " + id);
        this.wand = wand;
        this.mapId = mapId;
        this.id = id;
        MapConfig m = wand.plugin().registry().map(mapId);
        Map<String, Object> sc = m == null ? null : ScriptStore.find(ScriptStore.cached(m), id);
        if (sc == null) return;
        build(sc, p);
    }

    private void build(Map<String, Object> sc, Player p0) {
        String type = ScriptStore.str(sc, "type", "button").toLowerCase(Locale.ROOT);
        boolean hold = type.equals("hold"), area = type.equals("area"), zoneLike = area || type.equals("zone");
        int[] b = ScriptStore.block(sc);
        Region reg = area ? ScriptStore.area(sc) : null;
        String blockStr = reg != null ? reg.minX() + " " + reg.minY() + " " + reg.minZ() + " .. " + reg.maxX() + " " + reg.maxY() + " " + reg.maxZ()
                : b == null ? "?" : b[0] + " " + b[1] + " " + b[2];

        // ---------- верхний ряд: параметры
        setClick(0, Items.icon(Material.NAME_TAG, "<gold><bold>" + Msg.esc(id), List.of(
                (area ? "<gray>Область: <white>" : "<gray>Блок: <white>") + blockStr, "<gray>Карта: <white>" + Msg.esc(mapId), "",
                "<yellow>ЛКМ <gray>— переименовать", area ? "<yellow>ПКМ <gray>— перенести (центр низа — на блок)" : "<yellow>ПКМ <gray>— перенести на другой блок"), false), (p, c) -> {
            if (c.isRightClick() && reg != null) {
                wand.pick(p, ScriptWand.PickKind.MOVE, "<aqua>ЛКМ палочкой по полу, где должна стоять зона <white>" + id, at -> {
                    int cx = Math.floorDiv(reg.minX() + reg.maxX(), 2), cz = Math.floorDiv(reg.minZ() + reg.maxZ(), 2);
                    int dx = at.getBlockX() - cx, dy = at.getBlockY() + 1 - reg.minY(), dz = at.getBlockZ() - cz;
                    save(p, e -> ScriptStore.putArea(e, new Region(reg.minX() + dx, reg.minY() + dy, reg.minZ() + dz, reg.maxX() + dx, reg.maxY() + dy, reg.maxZ() + dz)), "Зона перенесена");
                });
            } else if (c.isRightClick()) {
                wand.pick(p, ScriptWand.PickKind.MOVE, "<aqua>ЛКМ палочкой по новому блоку для скрипта <white>" + id, at ->
                        save(p, e -> e.put("block", at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ()), "Скрипт перенесён"));
            } else {
                wand.ask(p, "<aqua>Новый id скрипта <gray>(латиница, цифры, _):", text -> {
                    String nid = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
                    if (nid.isEmpty()) { Msg.send(p, "<red>Пустой id"); wand.reopenEditor(p); return; }
                    boolean[] dup = {false};
                    ScriptStore.edit(wand.plugin(), mapId, list -> {
                        if (ScriptStore.find(list, nid) != null) { dup[0] = true; return; }
                        Map<String, Object> e = ScriptStore.find(list, id);
                        if (e != null) e.put("id", nid);
                    });
                    if (dup[0]) { Msg.send(p, "<red>Скрипт " + nid + " уже есть"); wand.reopenEditor(p); return; }
                    wand.openEditor(p, mapId, nid);
                });
            }
        });

        if (area) {
            set(2, Items.icon(typeIcon(type), "<white>Тип: <aqua>" + ScriptWand.typeName(type), List.of(
                    "<gray>Игрок внутри параллелепипеда.", "<dark_gray>Тип зоны-области не меняется:", "<dark_gray>удали её и сделай другой скрипт."), false), null);
            Region sel = wand.selection(p0);
            List<String> lore = new ArrayList<>(List.of("<gray>Сейчас: <white>" + ScriptStore.size(reg), "<gray>" + blockStr, ""));
            lore.add(sel != null ? "<yellow>Клик <gray>— заменить на выделение " + ScriptStore.size(sel) : "<dark_gray>Выдели новую область топором,");
            if (sel == null) lore.add("<dark_gray>потом нажми сюда (/mgp setup wand)");
            set(4, Items.icon(Material.GOLDEN_AXE, "<white>Область: <aqua>" + ScriptStore.size(reg), lore, false), p -> {
                Region s2 = wand.selection(p);
                if (s2 == null) { Msg.send(p, "<red>Нет выделения.</red> <gray>Возьми топор (/mgp setup wand): ЛКМ и ПКМ по углам, затем снова сюда."); return; }
                save(p, e -> ScriptStore.putArea(e, s2), "Область — " + ScriptStore.size(s2));
            });
        } else {
            setClick(2, Items.icon(typeIcon(type), "<white>Срабатывает: <aqua>" + ScriptWand.typeName(type), List.of(
                    "<gray>Удержание Shift / ПКМ по блоку / Наступить / Зона", "", "<yellow>Клик <gray>— следующий вариант"), false),
                    (p, c) -> save(p, e -> e.put("type", cycle(TYPES, type, c.isRightClick())), null));
        }
        String on = ScriptStore.str(sc, "on", "enter").toLowerCase(Locale.ROOT);
        if (zoneLike) {
            String onName = switch (on) {
                case "inside" -> "пока игрок внутри";
                case "leave" -> "когда игрок выходит";
                default -> "когда игрок входит";
            };
            setClick(3, Items.icon(Material.TRIPWIRE_HOOK, "<white>Когда: <aqua>" + onName, List.of(
                    "<gray>входит — один раз при входе;",
                    "<gray>внутри — снова и снова, раз в",
                    "<gray>  «перезарядку» (например, урон, отталкивание);",
                    "<gray>выходит — при выходе из зоны.", "", "<yellow>Клик <gray>— следующий вариант"), false),
                    (p, c) -> save(p, e -> {
                        String next = cycle(ONS, on, c.isRightClick());
                        e.put("on", next);
                        if (next.equals("inside") && !e.containsKey("cooldown")) e.put("cooldown", 1);
                    }, null));
            int need = (int) ScriptStore.num(sc, "players", 1);
            setClick(8, Items.icon(Material.PLAYER_HEAD, "<white>Игроков внутри: <aqua>" + (need <= 1 ? "хватит одного" : "не меньше " + need), List.of(
                    "<gray>Сработает, только когда в зоне", "<gray>одновременно столько игроков.",
                    "<gray>Действия — для всех, кто внутри", "<gray>(например, лифт для группы).", "",
                    "<yellow>ЛКМ <gray>+1  <yellow>ПКМ <gray>−1"), false),
                    (p, c) -> save(p, e -> { int v = (int) clamp(need + (c.isRightClick() ? -1 : 1), 1, 100); if (v <= 1) e.remove("players"); else e.put("players", v); }, null));
        }

        if (hold) {
            double h = ScriptStore.num(sc, "hold", 3);
            setClick(3, Items.icon(Material.CLOCK, "<white>Держать Shift: <aqua>" + ScriptWand.fmt(h) + " с", numLore("0.5", "2"), false),
                    (p, c) -> save(p, e -> e.put("hold", clamp(h + step(c, 0.5, 2), 0.5, 60)), null));
        }
        double defR = type.equals("zone") ? 2.0 : 1.8;
        double r = ScriptStore.num(sc, "radius", defR);
        if (hold || type.equals("zone")) {
            setClick(4, Items.icon(Material.COMPASS, "<white>Радиус: <aqua>" + ScriptWand.fmt(r) + " бл.", numLore("0.5", "2"), false),
                    (p, c) -> save(p, e -> e.put("radius", clamp(r + step(c, 0.5, 2), 0.5, 16)), null));
        }
        int defCd = hold || on.equals("inside") ? 1 : 2;
        int cd = (int) ScriptStore.num(sc, "cooldown", defCd);
        setClick(5, Items.icon(Material.HOPPER, zoneLike && on.equals("inside") ? "<white>Повтор каждые: <aqua>" + cd + " с" : "<white>Перезарядка: <aqua>" + cd + " с", numLore("1", "10"), false),
                (p, c) -> save(p, e -> e.put("cooldown", (int) clamp(cd + step(c, 1, 10), 0, 3600)), null));

        String mode = ScriptStore.str(sc, "mode", "REPEAT").toUpperCase(Locale.ROOT).replace('-', '_');
        String modeName = switch (mode) {
            case "ONCE" -> "один раз за игру";
            case "PER_PLAYER" -> "один раз для каждого игрока";
            default -> "каждый раз";
        };
        setClick(6, Items.icon(Material.REPEATER, "<white>Срабатывает: <aqua>" + modeName, List.of("", "<yellow>Клик <gray>— следующий вариант"), false),
                (p, c) -> save(p, e -> e.put("mode", cycle(MODES, mode, c.isRightClick())), null));

        if (hold || zoneLike) {
            String part = ScriptStore.str(sc, "particle", hold ? "PORTAL" : "none");
            setClick(7, Items.icon(Material.BLAZE_POWDER, "<white>Частицы: <aqua>" + part, List.of(hold ? "<gray>Крутятся вокруг блока" : "<gray>Граница зоны на полу (в игре)", "", "<yellow>Клик <gray>— следующие"), false),
                    (p, c) -> save(p, e -> e.put("particle", cycle(PARTICLES, part, c.isRightClick())), null));
        }
        if (hold) {
            String hint = ScriptStore.str(sc, "hint", "");
            String prog = ScriptStore.str(sc, "progress", "<aqua>Активация");
            setClick(8, Items.icon(Material.OAK_SIGN, "<white>Надписи", List.of(
                    "<gray>Подсказка рядом с блоком:", hint.isEmpty() ? "<dark_gray>нет" : hint,
                    "<gray>Шкала прогресса:", prog, "",
                    "<yellow>ЛКМ <gray>— изменить подсказку", "<yellow>ПКМ <gray>— изменить надпись шкалы"), false), (p, c) -> {
                String key = c.isRightClick() ? "progress" : "hint";
                wand.ask(p, c.isRightClick() ? "<aqua>Надпись на шкале прогресса, например <white><aqua>Телепорт в замок" : "<aqua>Подсказка, когда игрок рядом, например <white><yellow>Зажми SHIFT, чтобы спуститься",
                        text -> save(p, e -> e.put(key, text), "Сохранено"));
            });
        } else if (type.equals("button")) {
            boolean cancel = Boolean.parseBoolean(ScriptStore.str(sc, "cancel-use", "false"));
            set(8, Items.icon(Material.LEVER, "<white>Сам блок: <aqua>" + (cancel ? "не срабатывает" : "срабатывает как обычно"), List.of(
                    "<gray>Например, дверь не откроется, а сундук", "<gray>не покажет содержимое, если «не срабатывает».", "", "<yellow>Клик <gray>— переключить"), false),
                    p -> save(p, e -> e.put("cancel-use", !cancel), null));
        }

        for (int i = 9; i < 18; i++) set(i, Items.icon(Material.GRAY_STAINED_GLASS_PANE, "<dark_gray>Действия ниже — по порядку", List.of(), false), null);

        // ---------- действия
        List<String> acts = ScriptStore.actions(sc);
        for (int i = 0; i < Math.min(acts.size(), ACTION_SLOTS); i++) {
            String a = acts.get(i);
            int idx = i;
            List<String> lore = new ArrayList<>();
            lore.add("<dark_gray>" + Msg.esc(a.length() > 60 ? a.substring(0, 60) + "…" : a));
            lore.add("");
            lore.add("<yellow>ЛКМ <gray>— проверить на себе");
            lore.add("<yellow>ПКМ <gray>— изменить");
            lore.add("<yellow>Shift+ЛКМ <gray>— выше");
            lore.add("<red>Shift+ПКМ <gray>— удалить");
            setClick(FIRST_ACTION + i, Items.icon(ActionInfo.icon(a), "<white>" + (i + 1) + ". " + ActionInfo.title(a), lore, false), (p, c) -> {
                if (c == ClickType.SHIFT_RIGHT) {
                    save(p, e -> { List<String> l = ScriptStore.actions(e); if (idx < l.size()) l.remove(idx); e.put("actions", l); }, "Действие удалено");
                } else if (c == ClickType.SHIFT_LEFT) {
                    if (idx == 0) { wand.reopenEditor(p); return; }
                    save(p, e -> { List<String> l = ScriptStore.actions(e); if (idx < l.size()) Collections.swap(l, idx, idx - 1); e.put("actions", l); }, null);
                } else if (c.isRightClick()) {
                    edit(p, idx, a);
                } else {
                    p.closeInventory();
                    ActionPreview.run(wand, p, List.of(a));
                }
            });
        }
        if (acts.size() < ACTION_SLOTS) {
            set(FIRST_ACTION + acts.size(), Items.icon(Material.LIME_STAINED_GLASS_PANE, "<green><bold>+ Добавить действие", List.of("<gray>Телепорт, сообщение, звук, эффект..."), acts.isEmpty()),
                    p -> new ActionMenu(wand, p, mapId, id, -1).open(p));
        }

        // ---------- нижний ряд
        set(45, Items.icon(Material.LIME_DYE, "<green><bold>Добавить действие", List.of("<gray>Телепорт, сообщение, звук, эффект..."), acts.isEmpty()),
                p -> new ActionMenu(wand, p, mapId, id, -1).open(p));
        set(47, Items.icon(Material.ENDER_EYE, "<aqua><bold>Проверить всё на себе", List.of(
                "<gray>Выполнит действия по порядку на тебе:", "<gray>телепорт, сообщения, звуки, эффекты.",
                "<dark_gray>Блоки ставятся только у тебя на экране,", "<dark_gray>команды проверяются в игре."), false), p -> {
            p.closeInventory();
            ActionPreview.run(wand, p, acts);
        });
        set(49, Items.icon(Material.BOOK, "<yellow>Все скрипты карты", List.of(), false), p -> new ScriptListMenu(wand, p, mapId).open(p));
        set(51, Items.icon(Material.ARROW, "<gray>Закрыть", List.of("<dark_gray>Всё уже сохранено"), false), p -> { p.closeInventory(); wand.deselect(p); });
        setClick(53, Items.icon(Material.LAVA_BUCKET, "<red><bold>Удалить скрипт", List.of("<red>Shift+клик <gray>— удалить навсегда"), false), (p, c) -> {
            if (!c.isShiftClick()) { p.sendActionBar(Msg.parse("<red>Чтобы удалить, нажми с Shift")); return; }
            ScriptStore.edit(wand.plugin(), mapId, list -> list.removeIf(e -> id.equals(String.valueOf(e.get("id")))));
            p.closeInventory();
            wand.deselect(p);
            Msg.send(p, "<green>Скрипт " + id + " удалён");
        });
    }

    /** ПКМ по действию: телепорт — выбрать точку заново, остальное — ввести строку целиком. */
    private void edit(Player p, int idx, String a) {
        if (ActionInfo.teleportTarget(a) != null) {
            wand.pick(p, ScriptWand.PickKind.TELEPORT, ActionMenu.TELEPORT_HINT, at -> replace(p, idx, "teleport: " + ActionMenu.pos(at)));
            return;
        }
        Msg.send(p, "<gray>Сейчас: <white>" + Msg.esc(a));
        wand.ask(p, "<aqua>Новое действие целиком <gray>(тип: аргументы, как в строке выше)", text -> replace(p, idx, text));
    }

    private void replace(Player p, int idx, String action) {
        save(p, e -> { List<String> l = ScriptStore.actions(e); if (idx < l.size()) l.set(idx, action); else l.add(action); e.put("actions", l); }, "Действие изменено");
    }

    private void save(Player p, Consumer<Map<String, Object>> fn, String msg) {
        if (!ScriptStore.editOne(wand.plugin(), mapId, id, fn)) { Msg.send(p, "<red>Не удалось сохранить — скрипта больше нет?"); return; }
        if (msg != null) p.sendActionBar(Msg.parse("<green>" + msg));
        wand.openEditor(p, mapId, id);
    }

    private static Material typeIcon(String type) {
        return switch (type) {
            case "hold" -> Material.ENDER_EYE;
            case "plate" -> Material.STONE_PRESSURE_PLATE;
            case "zone" -> Material.LIME_STAINED_GLASS;
            case "area" -> Material.PINK_STAINED_GLASS;
            default -> Material.STONE_BUTTON;
        };
    }

    private static List<String> numLore(String small, String big) {
        return List.of("", "<yellow>ЛКМ <gray>+" + small + "  <yellow>ПКМ <gray>−" + small, "<yellow>Shift <gray>— по " + big);
    }

    private static double step(ClickType c, double small, double big) {
        double v = c.isShiftClick() ? big : small;
        return c.isRightClick() ? -v : v;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, Math.round(v * 10) / 10.0));
    }

    private static String cycle(List<String> list, String cur, boolean back) {
        int i = -1;
        for (int k = 0; k < list.size(); k++) if (list.get(k).equalsIgnoreCase(cur)) i = k;
        int n = list.size();
        return list.get(((back ? i - 1 : i + 1) % n + n) % n);
    }
}
