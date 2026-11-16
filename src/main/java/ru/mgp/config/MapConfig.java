package ru.mgp.config;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.mgp.util.Cfg;
import ru.mgp.util.Pos;
import ru.mgp.util.Region;

import java.io.File;
import java.util.*;

/** Карта: maps/<id>.yml. Координаты задаются без мира — карта копируется в новый мир на каждую игру. */
public final class MapConfig {
    public record ChestSpot(Pos pos, String tier) {}
    /** Продавец: set — сделки жителя из trades режима, shop — магазин из shops/*.yml (одно из двух). */
    public record TraderSpot(Pos pos, String set, String name, String profession, String shop) {}
    public record Trigger(String name, Region region, Mode mode, List<String> actions) {
        public enum Mode { ONCE, PER_PLAYER, REPEAT }
    }
    public record Plot(Region region, Pos spawn) {}
    /**
     * Спавн с командой и группой (spawn-points). team — для кого (роль или команда режима, например seeker / hider);
     * пусто — для всех. group — имя группы: её включают и выключают действием «spawns: enable <группа>» из триггера,
     * скрипта или цели. enabled: false — группа закрыта до включения. Из открытых спавнов берутся спавны с наибольшим
     * priority (по умолчанию 0, у закрытых сначала групп — 1: открылся новый рубеж — возрождаются уже там).
     */
    public record SpawnPoint(Pos pos, String team, String group, boolean enabled, int priority) {}
    /**
     * Цель на разрушение (objectives): когда в области разрушено percent% исходных твёрдых блоков,
     * выполняются actions (один раз за игру). show — шкала прогресса у игроков.
     */
    public record Objective(String id, String name, Region region, double percent, boolean show, List<String> actions) {}
    /**
     * Скриптовый объект карты вместо командных блоков (см. session/MapScripts).
     * type: hold — встать рядом с блоком и держать Shift hold сек.; button — ПКМ по блоку (кнопка, рычаг, люк...);
     *       plate — наступить на блок (нажимная плита); zone — войти в радиус.
     */
    public record Script(String id, Type type, Pos block, double radius, double hold, String hint, String progress,
                         String particle, int cooldown, Trigger.Mode mode, boolean cancelUse, List<String> actions) {
        public enum Type { HOLD, BUTTON, PLATE, ZONE }
    }

    public final String id;
    public final File file;
    public final YamlConfiguration yaml;
    public final String mode;
    /** Дополнительные режимы, в которых тоже играется карта (also-modes). */
    public final List<String> alsoModes = new ArrayList<>();
    public final String displayName;
    public final Material icon;
    public final String template;
    public final double weight;
    public final List<String> description;
    public final List<Pos> spawns = new ArrayList<>();
    public final List<SpawnPoint> spawnPoints = new ArrayList<>();
    public final List<Objective> objectives = new ArrayList<>();
    public final Pos spectator;
    public final Pos center;
    /** Биом для всей карты (id, например plains или minecraft:snowy_plains); null — как в шаблоне. */
    public final String biome;
    /** Геймрулы только для этой карты (поверх общих и режима). */
    public final java.util.Map<String, String> gamerules = new java.util.LinkedHashMap<>();
    public final double borderSize;
    public final List<ChestSpot> chests = new ArrayList<>();
    public final List<TraderSpot> traders = new ArrayList<>();
    public final List<Trigger> triggers = new ArrayList<>();
    public final List<Plot> plots = new ArrayList<>();
    /** Билд баттл: стены между участками (исчезают, когда время стройки вышло). */
    public final List<Region> walls = new ArrayList<>();
    /** Билд баттл: центр арены, куда переносит всех после стройки и в конце (по умолчанию spectator). */
    public final Pos arena;
    public final List<Script> scripts = new ArrayList<>();

    public boolean playableIn(String modeId) {
        if (mode.equalsIgnoreCase(modeId)) return true;
        for (String am : alsoModes) if (am.equalsIgnoreCase(modeId)) return true;
        return false;
    }

    public MapConfig(String id, File file, YamlConfiguration y) {
        this.id = id;
        this.file = file;
        this.yaml = y;
        this.mode = y.getString("mode", "");
        for (String am : y.getStringList("also-modes")) if (!am.isBlank()) alsoModes.add(am.trim().toLowerCase(java.util.Locale.ROOT));
        this.displayName = y.getString("display-name", id);
        this.icon = Cfg.material(y.getString("icon"), Material.FILLED_MAP);
        this.template = y.getString("template", id);
        this.weight = y.getDouble("weight", 1.0);
        this.description = y.getStringList("description");
        for (String s : y.getStringList("spawns")) spawns.add(Pos.parse(s));
        this.spectator = Pos.parse(y.getString("spectator"));
        this.center = Pos.parse(y.getString("center"));
        this.arena = Pos.parse(y.getString("arena"));
        String bi = y.getString("biome");
        this.biome = bi == null || bi.isBlank() || bi.equalsIgnoreCase("none") ? null : bi.trim().toLowerCase(java.util.Locale.ROOT);
        org.bukkit.configuration.ConfigurationSection gr = y.getConfigurationSection("gamerules");
        if (gr != null) for (String k : gr.getKeys(false)) gamerules.put(k, String.valueOf(gr.get(k)));
        this.borderSize = y.getDouble("border-size", 0);
        // "x y z tier"
        for (String s : y.getStringList("chests")) {
            String[] p = s.trim().split("\\s+");
            chests.add(new ChestSpot(Pos.parse(p[0] + " " + p[1] + " " + p[2]), p.length > 3 ? p[3] : "tier1"));
        }
        for (Map<?, ?> raw : y.getMapList("traders")) {
            Map<String, Object> m = Cfg.asMap(raw);
            traders.add(new TraderSpot(Pos.parse(Cfg.str(m, "pos", null)), Cfg.str(m, "set", "default"),
                    Cfg.str(m, "name", "<yellow>Торговец"), Cfg.str(m, "profession", "librarian"), Cfg.str(m, "shop", null)));
        }
        for (Map<?, ?> raw : y.getMapList("triggers")) {
            Map<String, Object> m = Cfg.asMap(raw);
            Region r = Region.of(Pos.parse(Cfg.str(m, "min", null)), Pos.parse(Cfg.str(m, "max", null)));
            triggers.add(new Trigger(Cfg.str(m, "name", "trigger" + triggers.size()), r,
                    Cfg.enumValue(Trigger.Mode.class, Cfg.str(m, "mode", "ONCE"), Trigger.Mode.ONCE),
                    Cfg.strings(m.get("actions"))));
        }
        for (Map<?, ?> raw : y.getMapList("scripts")) {
            Map<String, Object> m = Cfg.asMap(raw);
            Pos b = Pos.parse(Cfg.str(m, "block", null));
            if (b == null) continue;
            Script.Type t = Cfg.enumValue(Script.Type.class, Cfg.str(m, "type", "BUTTON"), Script.Type.BUTTON);
            scripts.add(new Script(Cfg.str(m, "id", "script" + scripts.size()), t, b,
                    Cfg.dbl(m, "radius", t == Script.Type.ZONE ? 2.0 : 1.8),
                    Cfg.dbl(m, "hold", 3.0),
                    Cfg.str(m, "hint", t == Script.Type.HOLD ? "<yellow>Зажми <gold><bold>SHIFT</bold></gold>, чтобы активировать" : null),
                    Cfg.str(m, "progress", "<aqua>Активация"),
                    Cfg.str(m, "particle", t == Script.Type.HOLD ? "PORTAL" : null),
                    Cfg.integer(m, "cooldown", t == Script.Type.HOLD ? 1 : 2),
                    Cfg.enumValue(Trigger.Mode.class, Cfg.str(m, "mode", "REPEAT").replace('-', '_'), Trigger.Mode.REPEAT),
                    Cfg.bool(m, "cancel-use", false),
                    Cfg.strings(m.get("actions"))));
        }
        for (Map<?, ?> raw : y.getMapList("spawn-points")) {
            Map<String, Object> m = Cfg.asMap(raw);
            Pos pos = Pos.parse(Cfg.str(m, "pos", null));
            if (pos == null) continue;
            String team = blankToNull(Cfg.str(m, "team", null)), group = blankToNull(Cfg.str(m, "group", null));
            boolean en = Cfg.bool(m, "enabled", true);
            spawnPoints.add(new SpawnPoint(pos, team == null ? null : team.toLowerCase(java.util.Locale.ROOT),
                    group == null ? null : group.toLowerCase(java.util.Locale.ROOT), en, Cfg.integer(m, "priority", en ? 0 : 1)));
        }
        for (Map<?, ?> raw : y.getMapList("objectives")) {
            Map<String, Object> m = Cfg.asMap(raw);
            Pos a = Pos.parse(Cfg.str(m, "min", null)), b = Pos.parse(Cfg.str(m, "max", null));
            if (a == null || b == null) continue;
            String oid = Cfg.str(m, "id", "objective" + objectives.size());
            objectives.add(new Objective(oid, Cfg.str(m, "name", "<red>" + oid), Region.of(a, b),
                    Math.max(1, Math.min(100, Cfg.dbl(m, "percent", 50))), Cfg.bool(m, "show", true), Cfg.strings(m.get("actions"))));
        }
        for (Map<?, ?> raw : y.getMapList("plots")) {
            Map<String, Object> m = Cfg.asMap(raw);
            plots.add(new Plot(Region.of(Pos.parse(Cfg.str(m, "min", null)), Pos.parse(Cfg.str(m, "max", null))),
                    Pos.parse(Cfg.str(m, "spawn", null))));
        }
        for (Map<?, ?> raw : y.getMapList("walls")) {
            Map<String, Object> m = Cfg.asMap(raw);
            Pos a = Pos.parse(Cfg.str(m, "min", null)), b = Pos.parse(Cfg.str(m, "max", null));
            if (a != null && b != null) walls.add(Region.of(a, b));
        }
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
