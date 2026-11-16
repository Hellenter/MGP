package ru.mgp.logic;

import ru.mgp.session.GameSession;

/** Регистрация режимов по полю type в modes/*.yml. Новый режим — новая строка здесь. */
public final class GameLogics {
    /** Типы режимов, которые знает плагин (для админ-панели). */
    public static final java.util.List<String> TYPES = java.util.List.of("hunger_games", "build_battle", "hide_and_seek", "siege", "assault");

    private GameLogics() {}

    public static GameLogic create(String type, GameSession s) {
        return switch (type) {
            case "hunger_games" -> new HungerGamesLogic(s);
            case "build_battle" -> new BuildBattleLogic(s);
            case "hide_and_seek" -> new HideAndSeekLogic(s);
            case "siege" -> new SiegeLogic(s);
            case "assault" -> new AssaultLogic(s);
            default -> throw new IllegalArgumentException("Неизвестный тип режима: " + type);
        };
    }
}
