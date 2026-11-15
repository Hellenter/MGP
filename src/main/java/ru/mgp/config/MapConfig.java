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
    public record TraderSpot(Pos pos, String set, String name, String profession) {}
    public record Trigger(String name, Region region, Mode mode, List<String> actions) {
        public enum Mode { ONCE, PER_PLAYER, REPEAT }
    }
    public record Plot(Region region, Pos spawn) {}

    public final String id;
    public final File file;
    public final YamlConfiguration yaml;
    public final String mode;
    public final String displayName;
    public final Material icon;
    public final String template;
    public final double weight;
    public final List<String> description;
    public final List<Pos> spawns = new ArrayList<>();
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

    public MapConfig(String id, File file, YamlConfiguration y) {
        this.id = id;
        this.file = file;
        this.yaml = y;
        this.mode = y.getString("mode", "");
        this.displayName = y.getString("display-name", id);
        this.icon = Cfg.material(y.getString("icon"), Material.FILLED_MAP);
        this.template = y.getString("template", id);
        this.weight = y.getDouble("weight", 1.0);
        this.description = y.getStringList("description");
        for (String s : y.getStringList("spawns")) spawns.add(Pos.parse(s));
        this.spectator = Pos.parse(y.getString("spectator"));
        this.center = Pos.parse(y.getString("center"));
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
                    Cfg.str(m, "name", "<yellow>Торговец"), Cfg.str(m, "profession", "librarian")));
        }
        for (Map<?, ?> raw : y.getMapList("triggers")) {
            Map<String, Object> m = Cfg.asMap(raw);
            Region r = Region.of(Pos.parse(Cfg.str(m, "min", null)), Pos.parse(Cfg.str(m, "max", null)));
            triggers.add(new Trigger(Cfg.str(m, "name", "trigger" + triggers.size()), r,
                    Cfg.enumValue(Trigger.Mode.class, Cfg.str(m, "mode", "ONCE"), Trigger.Mode.ONCE),
                    Cfg.strings(m.get("actions"))));
        }
        for (Map<?, ?> raw : y.getMapList("plots")) {
            Map<String, Object> m = Cfg.asMap(raw);
            plots.add(new Plot(Region.of(Pos.parse(Cfg.str(m, "min", null)), Pos.parse(Cfg.str(m, "max", null))),
                    Pos.parse(Cfg.str(m, "spawn", null))));
        }
    }
}
