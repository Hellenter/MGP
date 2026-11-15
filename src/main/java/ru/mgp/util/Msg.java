package ru.mgp.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Map;

/** Работа с текстом: MiniMessage, плейсхолдеры вида {key}, форматирование времени. */
public final class Msg {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static String prefix = "";

    private Msg() {}

    public static void setPrefix(String p) { prefix = p == null ? "" : p; }

    public static Component parse(String s) {
        return MM.deserialize(s == null ? "" : s);
    }

    /** Значения плейсхолдеров — это фрагменты MiniMessage (доверенные). Имена игроков экранируй через {@link #esc}. */
    public static Component parse(String s, Map<String, String> ph) {
        return parse(apply(s, ph));
    }

    public static String apply(String s, Map<String, String> ph) {
        if (s == null) return "";
        if (ph == null || ph.isEmpty() || s.indexOf('{') < 0) return s;
        String out = s;
        for (Map.Entry<String, String> e : ph.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    public static String esc(String raw) { return MM.escapeTags(raw); }

    /** Текст для названий предметов: без курсива по умолчанию. */
    public static Component item(String s) {
        return parse(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static void send(CommandSender to, String mm) {
        to.sendMessage(parse(prefix + mm));
    }

    public static void title(Player p, String title, String sub) {
        p.showTitle(Title.title(parse(title), parse(sub),
                Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(2000), Duration.ofMillis(500))));
    }

    public static String time(int seconds) {
        if (seconds < 0) seconds = 0;
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }
}
