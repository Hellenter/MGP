package ru.mgp.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.mgp.util.Cfg;
import ru.mgp.util.ItemSpec;
import ru.mgp.util.Pos;
import ru.mgp.vote.VoteType;

import java.io.File;
import java.util.*;

/** Режим: modes/<id>.yml. Всё, что настраивается для режима целиком. */
public final class ModeConfig {
    public final String id;
    public final File file;
    public final YamlConfiguration yaml;
    public final String type;
    public final String displayName;
    public final Material icon;
    public final List<String> description;
    public final int minPlayers, maxPlayers, maxSessions, countdown, fastCountdown, endDelay, timeLimit;
    public final String waitingTemplate;
    public final Pos waitingSpawn;
    public final double voidY;
    /** time: время суток в игре и лобби ожидания, не идёт (6000 — полдень); -1 — не трогать. */
    public final long fixedTime;
    public final VoteType mapVoteType;
    public final int mapVoteOptions;
    public final Map<String, String> gamerules = new LinkedHashMap<>();
    public final boolean naturalMobs;
    public final List<ModifierGroup> modifiers = new ArrayList<>();
    public final Map<String, Kit> kits = new LinkedHashMap<>();
    public final String defaultKit;

    private static String blank(String s) { return s == null || s.isBlank() || s.equalsIgnoreCase("all") ? null : s.trim(); }
    public final List<ItemSpec> startItems = new ArrayList<>();
    public final Map<String, LootTable> loot = new LinkedHashMap<>();
    /** Предмет-маркер в контейнере на карте -> тир лута (loot-markers). */
    public final Map<String, String> lootMarkers = new LinkedHashMap<>();
    public static final Map<String, String> DEFAULT_MARKERS = Map.of("IRON_INGOT", "tier1", "GOLD_INGOT", "tier2");
    public final Map<String, List<TradeSpec>> trades = new LinkedHashMap<>();
    public final List<TimelineEvent> timeline = new ArrayList<>();
    public final List<String> winActions;
    public final String sidebarTitle;
    public final Map<String, List<String>> sidebar = new LinkedHashMap<>();
    public final ConfigurationSection settings;

    public ModeConfig(String id, File file, YamlConfiguration y) {
        this.id = id;
        this.file = file;
        this.yaml = y;
        this.type = y.getString("type", id);
        this.displayName = y.getString("display-name", id);
        this.icon = Cfg.material(y.getString("icon"), Material.GRASS_BLOCK);
        this.description = y.getStringList("description");
        this.minPlayers = Math.max(1, y.getInt("min-players", 2));
        this.maxPlayers = Math.max(minPlayers, y.getInt("max-players", 12));
        this.maxSessions = y.getInt("max-sessions", 4);
        this.countdown = y.getInt("countdown", 60);
        this.fastCountdown = y.getInt("fast-countdown", 10);
        this.endDelay = y.getInt("end-delay", 10);
        this.timeLimit = Cfg.seconds(y.get("time-limit"), 0);
        this.waitingTemplate = y.getString("waiting.template", "waiting_lobby");
        this.waitingSpawn = Pos.parse(y.getString("waiting.spawn", "0.5 65 0.5"));
        this.voidY = y.getDouble("waiting.void-y", 0);
        this.fixedTime = y.getLong("time", -1);
        this.mapVoteType = Cfg.enumValue(VoteType.class, y.getString("map-vote.type"), VoteType.MOST_VOTES);
        this.mapVoteOptions = y.getInt("map-vote.options", 0);
        this.naturalMobs = y.getBoolean("natural-mobs", false);
        String dk = y.getString("default-kit");
        this.defaultKit = dk == null || dk.isBlank() ? null : dk.trim();
        this.winActions = y.getStringList("win-actions");

        ConfigurationSection gr = y.getConfigurationSection("gamerules");
        if (gr != null) for (String k : gr.getKeys(false)) gamerules.put(k, String.valueOf(gr.get(k)));

        ConfigurationSection mods = y.getConfigurationSection("modifiers");
        if (mods != null) for (String gid : mods.getKeys(false)) {
            ConfigurationSection g = mods.getConfigurationSection(gid);
            if (g == null) continue;
            List<ModifierGroup.Option> opts = new ArrayList<>();
            ConfigurationSection os = g.getConfigurationSection("options");
            if (os != null) for (String oid : os.getKeys(false)) {
                ConfigurationSection o = os.getConfigurationSection(oid);
                if (o == null) continue;
                opts.add(new ModifierGroup.Option(oid, o.getString("display", oid),
                        Cfg.material(o.getString("icon"), Material.PAPER), o.getDouble("weight", 1.0),
                        o.getStringList("actions")));
            }
            modifiers.add(new ModifierGroup(gid, g.getString("display", gid), Cfg.material(g.getString("icon"), Material.CLOCK),
                    Cfg.enumValue(ModifierGroup.Selection.class, g.getString("selection"), ModifierGroup.Selection.RANDOM),
                    Cfg.enumValue(VoteType.class, g.getString("vote-type"), VoteType.MOST_VOTES),
                    g.getString("fixed"), opts));
        }

        ConfigurationSection ks = y.getConfigurationSection("kits");
        if (ks != null) for (String kid : ks.getKeys(false)) {
            ConfigurationSection k = ks.getConfigurationSection(kid);
            if (k == null) continue;
            List<ItemSpec> items = new ArrayList<>();
            for (Object o : k.getList("items", List.of())) items.add(ItemSpec.parse(o));
            kits.put(kid, new Kit(kid, k.getString("display", kid), Cfg.material(k.getString("icon"), Material.CHEST),
                    k.getString("permission"), k.getStringList("description"), items, k.getStringList("actions"),
                    blank(k.getString("team"))));
        }

        for (Object o : y.getList("start-items", List.of())) startItems.add(ItemSpec.parse(o));

        List<LootTable.Limit> limits = lootLimits(y.getConfigurationSection("loot-limits"));
        ConfigurationSection ls = y.getConfigurationSection("loot");
        if (ls != null) for (String tier : ls.getKeys(false)) {
            ConfigurationSection t = ls.getConfigurationSection(tier);
            if (t == null) continue;
            int[] rolls = Cfg.range(t.get("rolls"), 3, 6);
            List<LootTable.Entry> entries = new ArrayList<>();
            for (Map<?, ?> raw : t.getMapList("items")) {
                Map<String, Object> m = Cfg.asMap(raw);
                ItemSpec spec = ItemSpec.parse(m.get("item"));
                int[] amount = Cfg.range(m.get("amount"), spec.amount, spec.amount);
                entries.add(new LootTable.Entry(spec, Cfg.integer(m, "weight", 1), amount[0], amount[1]));
            }
            loot.put(tier, new LootTable(tier, rolls[0], rolls[1], entries, limits));
        }

        ConfigurationSection ts = y.getConfigurationSection("trades");
        if (ts != null) for (String set : ts.getKeys(false)) {
            List<TradeSpec> list = new ArrayList<>();
            for (Map<?, ?> raw : ts.getMapList(set)) {
                Map<String, Object> m = Cfg.asMap(raw);
                list.add(new TradeSpec(ItemSpec.parse(m.get("result")), ItemSpec.parse(m.get("cost")),
                        m.get("cost2") == null ? null : ItemSpec.parse(m.get("cost2")), Cfg.integer(m, "max-uses", 999)));
            }
            trades.put(set, list);
        }

        for (Map<?, ?> raw : y.getMapList("timeline")) {
            Map<String, Object> m = Cfg.asMap(raw);
            timeline.add(new TimelineEvent(Cfg.str(m, "name", ""), Cfg.seconds(m.get("at"), 0), Cfg.strings(m.get("actions"))));
        }
        timeline.sort(Comparator.comparingInt(TimelineEvent::at));

        this.sidebarTitle = y.getString("sidebar.title", displayName);
        ConfigurationSection sb = y.getConfigurationSection("sidebar");
        if (sb != null) for (String k : sb.getKeys(false)) if (sb.isList(k)) sidebar.put(k, sb.getStringList(k));

        ConfigurationSection st = y.getConfigurationSection("settings");
        this.settings = st != null ? st : y.createSection("settings");

        ConfigurationSection lm = y.getConfigurationSection("loot-markers");
        if (lm != null) {
            for (String k : lm.getKeys(false)) {
                Material mat = Material.matchMaterial(k);
                String tier = lm.getString(k);
                if (mat != null && tier != null && !tier.isBlank()) lootMarkers.put(mat.name(), tier.trim());
            }
        } else {
            lootMarkers.putAll(DEFAULT_MARKERS);
        }
    }

    /**
     * loot-limits: группа предметов -> не больше N штук на контейнер (суммарно по группе).
     * Ключ — имя предмета (DIAMOND), шаблон со звёздочкой (*_INGOT) или несколько через запятую ("IRON_INGOT, DIAMOND").
     */
    private static List<LootTable.Limit> lootLimits(ConfigurationSection sec) {
        if (sec == null) return List.of();
        List<LootTable.Limit> out = new ArrayList<>();
        for (String key : sec.getKeys(false)) {
            Set<Material> mats = EnumSet.noneOf(Material.class);
            for (String part : key.split(",")) {
                String pat = part.trim().toUpperCase(Locale.ROOT);
                if (pat.isEmpty()) continue;
                if (pat.contains("*")) {
                    java.util.regex.Pattern p = java.util.regex.Pattern.compile(pat.replace("*", ".*"));
                    for (Material m : Material.values()) if (!m.name().startsWith("LEGACY_") && m.isItem() && p.matcher(m.name()).matches()) mats.add(m);
                } else {
                    Material m = Material.matchMaterial(pat);
                    if (m != null) mats.add(m);
                }
            }
            if (!mats.isEmpty()) out.add(new LootTable.Limit(mats, Math.max(0, sec.getInt(key, 1))));
        }
        return out;
    }

    /** Заполнять ли контейнеры лутом: settings.chest-loot (по умолчанию — только у голодных игр) и есть таблицы loot. */
    public boolean chestLoot() {
        return !loot.isEmpty() && settings.getBoolean("chest-loot", "hunger_games".equalsIgnoreCase(type));
    }
}
