package ru.mgp.stats;

import java.util.Locale;

/** Показатель статистики: по нему строятся топы и голограммы. */
public enum Stat {
    WINS("wins", "Победы", "победам"),
    KILLS("kills", "Убийства", "убийствам"),
    DEATHS("deaths", "Смерти", "смертям"),
    PLAYED("played", "Сыграно игр", "сыгранным играм"),
    KD("kd", "K/D", "K/D");

    public final String id;
    /** Название в меню: «Победы». */
    public final String title;
    /** Для заголовка топа: «Топ по победам». */
    public final String byTitle;

    Stat(String id, String title, String byTitle) {
        this.id = id;
        this.title = title;
        this.byTitle = byTitle;
    }

    public static Stat parse(String s) {
        if (s == null) return null;
        String k = s.toLowerCase(Locale.ROOT).replace("/", "");
        for (Stat st : values()) if (st.id.equals(k)) return st;
        return switch (k) {
            case "win", "победы" -> WINS;
            case "kill", "убийства" -> KILLS;
            case "death", "смерти" -> DEATHS;
            case "games", "game", "игры" -> PLAYED;
            default -> null;
        };
    }

    /** Значение для показа: K/D с двумя знаками, остальное целым. */
    public String format(double v) {
        return this == KD ? String.format(Locale.ROOT, "%.2f", v) : String.valueOf((long) v);
    }
}
