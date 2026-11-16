package ru.mgp.command;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import ru.mgp.util.Msg;
import ru.mgp.util.Pos;

import java.util.*;

/**
 * Правка выделения pos1/pos2 как в WorldEdit и по граням параллелепипеда:
 *   expand <n> [обратно] [направление] | expand vert   — расширить (//expand)
 *   contract <n> [обратно] [направление]                — сузить (//contract)
 *   shift <n> [направление]                             — сдвинуть всю рамку (//shift)
 *   outset / inset <n> [-h|-v]                          — во все стороны (//outset, //inset)
 *   face <грань> <координата | =-коорд. | +n | -n | here> — одна грань: east/west/up/down/south/north (x+ x- y+ y- z+ z-);
 *                                                        +n наружу, -n внутрь, here — по твоей позиции
 *   box <x1> <y1> <z1> <x2> <y2> <z2>                   — задать параллелепипед целиком
 * Направление: north/south/east/west/up/down, x+ x- y+ y- z+ z-, me (куда смотришь, по умолчанию), back, left, right.
 * Углы pos1/pos2 сохраняют свой цвет: двигается тот угол, который стоит на нужной стороне.
 */
final class SelectionOps {
    /** Грань: ось 0=x 1=y 2=z, знак +1 / -1. */
    record Face(int axis, int sign) {
        String label() { return LABELS[axis * 2 + (sign > 0 ? 0 : 1)]; }
        String id() { return IDS[axis * 2 + (sign > 0 ? 0 : 1)]; }
        Face opposite() { return new Face(axis, -sign); }
    }

    static final String[] IDS = {"east", "west", "up", "down", "south", "north"};
    static final String[] LABELS = {"восток (X+)", "запад (X−)", "верх (Y+)", "низ (Y−)", "юг (Z+)", "север (Z−)"};
    static final List<Face> FACES = List.of(new Face(0, 1), new Face(0, -1), new Face(1, 1), new Face(1, -1), new Face(2, 1), new Face(2, -1));
    static final List<String> DIRECTIONS = List.of("me", "back", "left", "right", "up", "down", "north", "south", "east", "west");

    private final Map<UUID, Pos> pos1, pos2;
    private final Map<UUID, String> world;
    private final Runnable1 redraw;

    interface Runnable1 { void run(Player p); }

    SelectionOps(Map<UUID, Pos> pos1, Map<UUID, Pos> pos2, Map<UUID, String> world, Runnable1 redraw) {
        this.pos1 = pos1;
        this.pos2 = pos2;
        this.world = world;
        this.redraw = redraw;
    }

    // ------------------------------------------------------------------ модель

    private static int get(Pos p, int axis) { return axis == 0 ? p.bx() : axis == 1 ? p.by() : p.bz(); }

    private static Pos with(Pos p, int axis, int v) {
        return new Pos(axis == 0 ? v : p.bx(), axis == 1 ? v : p.by(), axis == 2 ? v : p.bz(), 0, 0);
    }

    boolean has(Player p) {
        UUID id = p.getUniqueId();
        return pos1.containsKey(id) && pos2.containsKey(id) && p.getWorld().getName().equals(world.get(id));
    }

    private boolean need(Player p) {
        if (has(p)) return true;
        Msg.send(p, "<red>Сначала выдели область: <yellow>/mgp setup pos1</yellow> и <yellow>pos2</yellow> (или <yellow>/mgp setup box</yellow>).");
        return false;
    }

    /** Координата грани (внешний блок этой стороны). */
    int face(Player p, Face f) {
        Pos a = pos1.get(p.getUniqueId()), b = pos2.get(p.getUniqueId());
        int va = get(a, f.axis()), vb = get(b, f.axis());
        return f.sign() > 0 ? Math.max(va, vb) : Math.min(va, vb);
    }

    /** Поставить грань в координату v. Если она перешла за противоположную — грани меняются местами. */
    void setFace(Player p, Face f, int v) {
        UUID id = p.getUniqueId();
        Pos a = pos1.get(id), b = pos2.get(id);
        int va = get(a, f.axis()), vb = get(b, f.axis());
        // угол, стоящий на этой стороне (при равенстве — pos2 для «+», pos1 для «−»)
        boolean moveA = f.sign() > 0 ? va > vb : va < vb;
        if (moveA) pos1.put(id, with(a, f.axis(), v)); else pos2.put(id, with(b, f.axis(), v));
    }

    void moveFace(Player p, Face f, int n) { setFace(p, f, face(p, f) + f.sign() * n); }

    String size(Player p) {
        UUID id = p.getUniqueId();
        Pos a = pos1.get(id), b = pos2.get(id);
        int dx = Math.abs(a.bx() - b.bx()) + 1, dy = Math.abs(a.by() - b.by()) + 1, dz = Math.abs(a.bz() - b.bz()) + 1;
        return dx + "×" + dy + "×" + dz + " (" + String.format(Locale.ROOT, "%,d", (long) dx * dy * dz).replace(',', ' ') + " блоков)";
    }

    void refresh(Player p) { redraw.run(p); }

    private void done(Player p, String what) {
        redraw.run(p);
        UUID id = p.getUniqueId();
        Msg.send(p, "<green>" + what + " <gray>→ " + size(p) + "  <red>■<gray>" + pos1.get(id).serialize() + "  <blue>■<gray>" + pos2.get(id).serialize());
    }

    // ------------------------------------------------------------------ направления

    /** Направление из аргумента; null — не направление. */
    static Face direction(Player p, String s) {
        s = s.toLowerCase(Locale.ROOT);
        switch (s) {
            case "east", "e", "x+", "+x", "восток" -> { return new Face(0, 1); }
            case "west", "w", "x-", "-x", "запад" -> { return new Face(0, -1); }
            case "up", "u", "y+", "+y", "вверх", "верх" -> { return new Face(1, 1); }
            case "down", "d", "y-", "-y", "вниз", "низ" -> { return new Face(1, -1); }
            case "south", "s", "z+", "+z", "юг" -> { return new Face(2, 1); }
            case "north", "n", "z-", "-z", "север" -> { return new Face(2, -1); }
            case "me", "f", "forward", "вперёд", "вперед" -> { return facing(p.getLocation(), 0); }
            case "back", "b", "назад" -> { return facing(p.getLocation(), 0).opposite(); }
            case "left", "l", "влево" -> { return facing(p.getLocation(), -90); }
            case "right", "r", "вправо" -> { return facing(p.getLocation(), 90); }
            default -> { return null; }
        }
    }

    /** Куда смотрит игрок (с учётом взгляда вверх/вниз, как в WE), с поворотом по горизонтали. */
    static Face facing(Location l, float turn) {
        if (turn == 0) {
            if (l.getPitch() < -50) return new Face(1, 1);
            if (l.getPitch() > 50) return new Face(1, -1);
        }
        float yaw = ((l.getYaw() + turn) % 360 + 360) % 360;
        int q = Math.round(yaw / 90f) % 4; // 0 юг, 1 запад, 2 север, 3 восток
        return switch (q) {
            case 0 -> new Face(2, 1);
            case 1 -> new Face(0, -1);
            case 2 -> new Face(2, -1);
            default -> new Face(0, 1);
        };
    }

    private static Integer num(String s) {
        try { return Integer.parseInt(s.startsWith("+") ? s.substring(1) : s); } catch (NumberFormatException e) { return null; }
    }

    // ------------------------------------------------------------------ команды

    /** a[0] = подкоманда setup (expand, contract, ...). true — обработано. */
    boolean handle(Player p, String[] a) {
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "expand", "contract" -> resize(p, a, sub.equals("expand"));
            case "shift" -> shift(p, a);
            case "outset", "inset" -> outset(p, a, sub.equals("outset"));
            case "face" -> face(p, a);
            case "box" -> box(p, a);
            case "size" -> { if (need(p)) done(p, "Выделение"); }
            case "sel", "selection", "faces" -> { if (need(p)) new SelectionMenu(this, p).open(p); }
            default -> { return false; }
        }
        return true;
    }

    /** expand <n> [обратно] [направление] | expand vert. */
    private void resize(Player p, String[] a, boolean expand) {
        String cmd = expand ? "expand" : "contract";
        if (a.length < 2) { Msg.send(p, "<red>/mgp setup " + cmd + " <n> [обратно] [направление]" + (expand ? " <gray>или <red>/mgp setup expand vert" : "")); return; }
        if (!need(p)) return;
        if (expand && a[1].equalsIgnoreCase("vert")) {
            int min = p.getWorld().getMinHeight(), max = p.getWorld().getMaxHeight() - 1;
            setFace(p, new Face(1, -1), min);
            setFace(p, new Face(1, 1), max);
            done(p, "Расширено по высоте мира (" + min + ".." + max + ")");
            return;
        }
        Integer n = num(a[1]);
        if (n == null) { Msg.send(p, "<red>Не число: " + Msg.esc(a[1])); return; }
        int rev = 0;
        Face dir = null;
        if (a.length > 2) {
            Integer r = num(a[2]);
            if (r != null) { rev = r; if (a.length > 3) dir = direction(p, a[3]); else dir = direction(p, "me"); if (a.length > 3 && dir == null) { badDir(p, a[3]); return; } }
            else { dir = direction(p, a[2]); if (dir == null) { badDir(p, a[2]); return; } }
        } else dir = direction(p, "me");
        if (expand) {
            moveFace(p, dir, n);
            if (rev != 0) moveFace(p, dir.opposite(), rev);
        } else {
            // как в WE: //contract 5 north подтягивает южную грань на 5 к северу
            moveFace(p, dir.opposite(), -n);
            if (rev != 0) moveFace(p, dir, -rev);
        }
        done(p, (expand ? "Расширено" : "Сужено") + " на " + n + (rev != 0 ? " и " + rev + " обратно" : "") + ": " + dir.label());
    }

    private void shift(Player p, String[] a) {
        if (a.length < 2) { Msg.send(p, "<red>/mgp setup shift <n> [направление]"); return; }
        if (!need(p)) return;
        Integer n = num(a[1]);
        if (n == null) { Msg.send(p, "<red>Не число: " + Msg.esc(a[1])); return; }
        Face dir = a.length > 2 ? direction(p, a[2]) : direction(p, "me");
        if (dir == null) { badDir(p, a[2]); return; }
        UUID id = p.getUniqueId();
        int d = dir.sign() * n;
        Pos x = pos1.get(id), y = pos2.get(id);
        pos1.put(id, with(x, dir.axis(), get(x, dir.axis()) + d));
        pos2.put(id, with(y, dir.axis(), get(y, dir.axis()) + d));
        done(p, "Сдвинуто на " + n + ": " + dir.label());
    }

    private void outset(Player p, String[] a, boolean out) {
        String cmd = out ? "outset" : "inset";
        if (a.length < 2) { Msg.send(p, "<red>/mgp setup " + cmd + " <n> [-h — только по горизонтали | -v — только по вертикали]"); return; }
        if (!need(p)) return;
        Integer n = num(a[1]);
        if (n == null) { Msg.send(p, "<red>Не число: " + Msg.esc(a[1])); return; }
        boolean h = a.length < 3 || !a[2].equalsIgnoreCase("-v"), v = a.length < 3 || !a[2].equalsIgnoreCase("-h");
        int d = out ? n : -n;
        for (Face f : FACES) if (f.axis() == 1 ? v : h) moveFace(p, f, d);
        done(p, (out ? "Расширено" : "Сужено") + " во все стороны на " + n + (h && v ? "" : h ? " (по горизонтали)" : " (по вертикали)"));
    }

    /** face <грань> <число | +n | -n | here> */
    private void face(Player p, String[] a) {
        if (a.length < 3) {
            Msg.send(p, "<red>/mgp setup face <грань> <координата | +n | -n | here>");
            Msg.send(p, "<gray>+n — грань наружу, -n — внутрь, отрицательная координата через =: <yellow>face north =-120");
            Msg.send(p, "<gray>Грани: east west up down south north (или x+ x- y+ y- z+ z-). Меню: <yellow>/mgp setup sel");
            return;
        }
        if (!need(p)) return;
        Face f = direction(p, a[1]);
        if (f == null) { badDir(p, a[1]); return; }
        String v = a[2].toLowerCase(Locale.ROOT);
        if (v.equals("here") || v.equals("тут")) {
            setFace(p, f, get(Pos.ofBlock(p.getLocation()), f.axis()));
        } else if (v.startsWith("+") || v.startsWith("-")) {
            // +n — грань наружу на n блоков, -n — внутрь
            Integer n = num(v);
            if (n == null) { Msg.send(p, "<red>Не число: " + Msg.esc(a[2])); return; }
            moveFace(p, f, n);
        } else {
            // число — точная координата; отрицательную пиши через «=»: face north =-120
            Integer n = num(v.startsWith("=") ? v.substring(1) : v);
            if (n == null) { Msg.send(p, "<red>Не число: " + Msg.esc(a[2])); return; }
            setFace(p, f, n);
        }
        done(p, "Грань " + f.label() + " = " + face(p, f));
    }

    private void box(Player p, String[] a) {
        if (a.length < 7) { Msg.send(p, "<red>/mgp setup box <x1> <y1> <z1> <x2> <y2> <z2> <gray>(~ — твоя координата)"); return; }
        Pos me = Pos.ofBlock(p.getLocation());
        int[] v = new int[6];
        for (int i = 0; i < 6; i++) {
            String s = a[i + 1];
            int base = get(me, i % 3);
            Integer n;
            if (s.startsWith("~")) n = s.length() == 1 ? Integer.valueOf(0) : num(s.substring(1));
            else n = num(s);
            if (n == null) { Msg.send(p, "<red>Не число: " + Msg.esc(s)); return; }
            v[i] = s.startsWith("~") ? base + n : n;
        }
        UUID id = p.getUniqueId();
        pos1.put(id, new Pos(v[0], v[1], v[2], 0, 0));
        pos2.put(id, new Pos(v[3], v[4], v[5], 0, 0));
        world.put(id, p.getWorld().getName());
        done(p, "Параллелепипед задан");
    }

    private static void badDir(Player p, String s) {
        Msg.send(p, "<red>Не понял направление «" + Msg.esc(s) + "». <gray>Можно: me back left right up down north south east west (или x+ x- y+ y- z+ z-)");
    }
}
