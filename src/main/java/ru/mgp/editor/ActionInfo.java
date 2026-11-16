package ru.mgp.editor;

import org.bukkit.Material;
import ru.mgp.util.Msg;
import ru.mgp.util.Pos;

import java.util.Locale;

/** Человекочитаемое описание строк-действий ("teleport: 1 2 3") для меню и подписей. */
public final class ActionInfo {
    private ActionInfo() {}

    public static String type(String raw) {
        int i = raw.indexOf(':');
        return (i < 0 ? raw : raw.substring(0, i)).trim().toLowerCase(Locale.ROOT);
    }

    public static String arg(String raw) {
        int i = raw.indexOf(':');
        return i < 0 ? "" : raw.substring(i + 1).trim();
    }

    public static Pos teleportTarget(String raw) {
        String t = type(raw);
        if (!t.equals("teleport") && !t.equals("tp")) return null;
        try {
            return Pos.parse(arg(raw));
        } catch (Exception e) {
            return null;
        }
    }

    public static Material icon(String raw) {
        return switch (type(raw)) {
            case "teleport", "tp" -> Material.ENDER_PEARL;
            case "message", "broadcast" -> Material.PAPER;
            case "title" -> Material.NAME_TAG;
            case "actionbar" -> Material.OAK_SIGN;
            case "sound" -> Material.NOTE_BLOCK;
            case "effect" -> Material.POTION;
            case "clear-effects" -> Material.MILK_BUCKET;
            case "give" -> Material.CHEST;
            case "heal" -> Material.GOLDEN_APPLE;
            case "max-health" -> Material.APPLE;
            case "setblock" -> Material.GRASS_BLOCK;
            case "particle", "particles" -> Material.BLAZE_POWDER;
            case "wait" -> Material.CLOCK;
            case "command" -> Material.COMMAND_BLOCK;
            case "mode" -> Material.BEACON;
            case "time" -> Material.CLOCK;
            case "weather" -> Material.WATER_BUCKET;
            case "border" -> Material.IRON_BARS;
            case "refill-chests" -> Material.CHEST_MINECART;
            case "spawns" -> Material.RESPAWN_ANCHOR;
            default -> Material.WRITABLE_BOOK;
        };
    }

    /** Коротко, для подписи над блоком. */
    public static String shortText(String raw) {
        return switch (type(raw)) {
            case "teleport", "tp" -> "→ телепорт";
            case "message", "broadcast" -> "сообщение";
            case "title" -> "заголовок";
            case "actionbar" -> "надпись над хотбаром";
            case "sound" -> "звук";
            case "effect" -> "эффект " + first(arg(raw));
            case "give" -> "выдать " + first(arg(raw)).toLowerCase(Locale.ROOT);
            case "heal" -> "лечение";
            case "setblock" -> "поставить блок";
            case "particle", "particles" -> "частицы";
            case "wait" -> "пауза " + arg(raw) + " с";
            case "spawns" -> (arg(raw).startsWith("disable") ? "закрыть" : "открыть") + " спавны";
            case "command" -> "команда";
            default -> type(raw);
        };
    }

    /** Название пункта в меню скрипта. */
    public static String title(String raw) {
        String a = arg(raw);
        return switch (type(raw)) {
            case "teleport", "tp" -> "<light_purple>Телепорт <gray>→ <white>" + Msg.esc(a);
            case "message" -> "<white>Сообщение: " + clip(a);
            case "broadcast" -> "<white>Сообщение всем: " + clip(a);
            case "title" -> "<white>Заголовок: " + clip(a);
            case "actionbar" -> "<white>Над хотбаром: " + clip(a);
            case "sound" -> "<aqua>Звук <gray>" + Msg.esc(a.replace("minecraft:", ""));
            case "effect" -> "<green>Эффект <gray>" + Msg.esc(a);
            case "clear-effects" -> "<green>Снять эффекты";
            case "give" -> "<gold>Выдать <gray>" + Msg.esc(a);
            case "heal" -> "<red>Вылечить";
            case "setblock" -> "<yellow>Поставить блок <gray>" + Msg.esc(a);
            case "particle", "particles" -> "<aqua>Частицы <gray>" + Msg.esc(a);
            case "wait" -> "<gray>Пауза <white>" + Msg.esc(a) + " с";
            case "command" -> "<dark_aqua>Команда <gray>/" + Msg.esc(a);
            case "mode" -> "<dark_aqua>Действие режима <gray>" + Msg.esc(a);
            default -> "<white>" + Msg.esc(raw);
        };
    }

    private static String first(String s) {
        String[] p = s.trim().split("\\s+");
        return p.length == 0 ? "" : p[0];
    }

    /** Текст с MiniMessage показываем как есть (он и так оформлен), но обрезаем длинный. */
    private static String clip(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }
}
