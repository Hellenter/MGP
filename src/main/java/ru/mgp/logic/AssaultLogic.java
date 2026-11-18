package ru.mgp.logic;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import ru.mgp.config.MapConfig;
import ru.mgp.session.ActionRunner;
import ru.mgp.session.GameSession;
import ru.mgp.shop.Shop;
import ru.mgp.shop.ShopMenu;
import ru.mgp.shop.ShopService;
import ru.mgp.util.*;

import java.util.*;

/**
 * Штурм (Assault) — большая осада по стадиям. Атакующие должны разрушить цели (objectives карты) на стороне
 * защитников, на каждую стадию своё время. Взяли все цели стадии — следующая стадия, спавны обеих команд
 * переезжают (spawn-points с группой stage<N>). Не успели — победа защиты. Взяли последнюю стадию — победа атаки.
 *   • Экономика: монеты за убийства, разрушение целей, оборону (убийство рядом с целью), взятую стадию и доход по таймеру.
 *     Тратятся в магазинах (shops/*.yml) на товары с price: "MONEY n", в том числе наборы — несколько предметов за раз.
 *   • После смерти игрок попадает в лимбо своей команды (maps → limbo): там магазин (изумруд в хотбаре или продавцы),
 *     выход — триггер или скрипт с действием «mode: deploy» — переносит на текущий спавн команды.
 *   • Цели ломаются взрывами (ТНТ, разрывные стрелы) атакующих; цели будущих стадий неуязвимы.
 *   • Команды делятся при старте (defender-ratio, пати вместе), ники противников скрыты.
 */
public final class AssaultLogic extends GameLogic {
    public enum Team {
        DEFENDERS("defenders", "<blue>", "Защитники", net.kyori.adventure.text.format.NamedTextColor.BLUE, 0x3c8dde),
        ATTACKERS("attackers", "<red>", "Атакующие", net.kyori.adventure.text.format.NamedTextColor.RED, 0xd23c3c);
        final String id, color, title;
        final net.kyori.adventure.text.format.NamedTextColor named;
        final int rgb;
        Team(String id, String color, String title, net.kyori.adventure.text.format.NamedTextColor named, int rgb) {
            this.id = id; this.color = color; this.title = title; this.named = named; this.rgb = rgb;
        }
        Team other() { return this == DEFENDERS ? ATTACKERS : DEFENDERS; }
    }

    /** Стадия: цели (id из objectives), сколько из них нужно (0 — все), время и действия при начале. */
    /** zones — где может быть команда в эту стадию (attackers / defenders → области), пусто — без ограничения стадии. */
    private record Stage(String name, int time, List<String> objectives, int require, String spawnGroup, List<String> actions,
                         Map<String, List<Region>> zones) {}

    /** Зоны одной стадии из yml (stages[i].zones): команда -> области. */
    public static Map<String, List<Region>> zones(Object raw) {
        Map<String, List<Region>> out = new HashMap<>();
        Map<String, Object> m = Cfg.asMap(raw);
        if (m == null) return out;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!(e.getValue() instanceof List<?> l)) continue;
            List<Region> rs = new ArrayList<>();
            for (Object o : l) {
                Map<String, Object> rm = Cfg.asMap(o);
                if (rm == null) continue;
                Pos a = Pos.parse(Cfg.str(rm, "min", null)), b = Pos.parse(Cfg.str(rm, "max", null));
                if (a != null && b != null) rs.add(Region.of(a, b));
            }
            if (!rs.isEmpty()) out.put(e.getKey().toLowerCase(Locale.ROOT), rs);
        }
        return out;
    }

    private static final String SHOP_ITEM = "assault_shop";
    private static final String DEPLOY_ITEM = "assault_deploy";

    // настройки
    private final double defenderRatio;
    private final int prepTime, stageTimeDefault, respawnDelay, limboTimeout;
    private final boolean keepInventory, dropItems, hideEnemyNames, enemyNameOnAim, autoTnt, defenderTnt, objectiveHandBreak, defendersRepair;
    private final String redeploy;
    private final int tntFuse;
    private final Set<Material> breakable, explosionProof;
    private final List<ItemSpec> defenderItems = new ArrayList<>(), attackerItems = new ArrayList<>();
    private final Map<Team, String> shops = new EnumMap<>(Team.class);
    // экономика
    private final int moneyStart, moneyMax, rewardKill, rewardDefense, rewardObjective, rewardObjectiveTeam,
            rewardStageAttackers, rewardStageDefenders, income, incomeEvery;
    private final double defenseRadius;

    // состояние
    private final Map<UUID, Team> teams = new HashMap<>();
    private final Map<UUID, Integer> money = new HashMap<>();
    private final List<Stage> stages = new ArrayList<>();
    private final Map<Team, Location> limbo = new EnumMap<>(Team.class);
    /** Игроки в лимбо → секунда игры, с которой можно выйти. */
    private final Map<UUID, Integer> inLimbo = new HashMap<>();
    /** Защита после выхода на поле: до какой секунды игры. */
    /** Кто ломал цель: id цели → игроки (награда за разрушение). */
    private final Map<String, Set<UUID>> contributors = new HashMap<>();
    private final Set<Long> placed = new HashSet<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private int stageIdx = -1, stageStartAt;
    private boolean hasExit, ended;

    public AssaultLogic(GameSession s) {
        super(s);
        ConfigurationSection st = s.mode.settings;
        defenderRatio = Math.max(0.05, Math.min(0.95, st.getDouble("defender-ratio", 0.5)));
        prepTime = Math.max(0, Cfg.seconds(st.get("prep-time"), 60));
        int t = Cfg.seconds(st.get("stage-time"), 0);
        stageTimeDefault = t > 0 ? t : 900;
        respawnDelay = Math.max(0, Cfg.seconds(st.get("respawn-delay"), 5));
        limboTimeout = Math.max(0, Cfg.seconds(st.get("limbo-timeout"), 0));
        keepInventory = st.getBoolean("keep-inventory", false);
        dropItems = st.getBoolean("drop-items", false);
        hideEnemyNames = st.getBoolean("hide-enemy-nametags", true);
        enemyNameOnAim = st.getBoolean("enemy-nametag-on-aim", true);
        autoTnt = st.getBoolean("tnt-auto-ignite", true);
        tntFuse = Math.max(1, (int) Math.round(st.getDouble("tnt-fuse", 3) * 20));
        defenderTnt = st.getBoolean("defender-tnt", false);
        objectiveHandBreak = st.getBoolean("objective-hand-break", false);
        defendersRepair = st.getBoolean("defenders-repair", false);
        redeploy = st.getString("redeploy-on-stage", "none").toLowerCase(Locale.ROOT);
        List<String> unknown = new ArrayList<>();
        breakable = Cfg.materials(st.getStringList("breakable-blocks"), unknown);
        explosionProof = Cfg.materials(st.isList("explosion-proof") ? st.getStringList("explosion-proof") : List.of("BEDROCK", "BARRIER"), unknown);
        if (!unknown.isEmpty()) s.plugin().getLogger().warning("Штурм " + s.mode.id + ": не распознаны блоки " + unknown);
        for (Object o : st.getList("defender-items", List.of())) defenderItems.add(ItemSpec.parse(o));
        for (Object o : st.getList("attacker-items", List.of())) attackerItems.add(ItemSpec.parse(o));
        // shop: <id> — общий магазин; shop: {attackers: <id>, defenders: <id>} — свой у каждой команды
        if (st.isConfigurationSection("shop")) {
            ConfigurationSection sh = st.getConfigurationSection("shop");
            for (Team tm : Team.values()) if (sh.getString(tm.id) != null) shops.put(tm, sh.getString(tm.id));
        } else if (st.getString("shop") != null) for (Team tm : Team.values()) shops.put(tm, st.getString("shop"));
        ConfigurationSection ec = st.getConfigurationSection("economy");
        if (ec == null) ec = st.createSection("economy");
        moneyStart = Math.max(0, ec.getInt("start", 800));
        moneyMax = Math.max(0, ec.getInt("max", 16000));
        rewardKill = ec.getInt("kill", 100);
        rewardDefense = ec.getInt("defense-kill", 75);
        defenseRadius = Math.max(0, ec.getDouble("defense-radius", 16));
        rewardObjective = ec.getInt("objective", 400);
        rewardObjectiveTeam = ec.getInt("objective-team", 200);
        rewardStageAttackers = ec.getInt("stage-attackers", 300);
        rewardStageDefenders = ec.getInt("stage-defenders", 300);
        income = ec.getInt("income", 50);
        incomeEvery = Math.max(0, Cfg.seconds(ec.get("income-every"), 60));
    }

    // ------------------------------------------------------------------ команды

    public Team team(Player p) { return teams.get(p.getUniqueId()); }

    @Override
    public String zoneTeam(Player p) {
        Team t = team(p);
        return t == null ? null : t.id;
    }

    private List<Player> members(Team t) {
        List<Player> out = new ArrayList<>();
        for (Player p : session.alivePlayers()) if (team(p) == t) out.add(p);
        return out;
    }

    // ------------------------------------------------------------------ карта: стадии и лимбо

    private void loadMap() {
        MapConfig m = session.map();
        stages.clear();
        int n = 0;
        for (Map<?, ?> raw : m.yaml.getMapList("stages")) {
            Map<String, Object> sm = Cfg.asMap(raw);
            n++;
            int time = Cfg.seconds(sm.get("time"), 0);
            String req = Cfg.str(sm, "require", "all");
            int require = req.equalsIgnoreCase("all") ? 0 : Math.max(0, parseInt(req));
            stages.add(new Stage(Cfg.str(sm, "name", "<yellow>Стадия " + n), time > 0 ? time : stageTimeDefault,
                    Cfg.strings(sm.get("objectives")), require, Cfg.str(sm, "spawn-group", "stage" + n).toLowerCase(Locale.ROOT),
                    Cfg.strings(sm.get("actions")), zones(sm.get("zones"))));
        }
        if (stages.isEmpty()) { // без стадий — одна стадия со всеми целями карты
            List<String> ids = new ArrayList<>();
            for (MapConfig.Objective o : m.objectives) ids.add(o.id());
            stages.add(new Stage("<yellow>Штурм", stageTimeDefault, ids, 0, "stage1", List.of(), Map.of()));
        }
        for (int i = 0; i < stages.size(); i++)
            if (stages.get(i).objectives().isEmpty())
                session.plugin().getLogger().warning("Штурм, карта " + m.id + ": у стадии " + (i + 1) + " нет целей — её можно только выстоять до конца времени");
        limbo.clear();
        ConfigurationSection lb = m.yaml.getConfigurationSection("limbo");
        if (lb != null) for (Team t : Team.values()) {
            Pos pos = Pos.parse(lb.getString(t.id));
            if (pos != null) limbo.put(t, pos.toLocation(session.gameWorld()));
        }
        if (limbo.size() < 2)
            session.plugin().getLogger().warning("Штурм, карта " + m.id + ": нет лимбо для " + (limbo.isEmpty() ? "обеих команд" : "одной из команд")
                    + " — игроки возрождаются сразу на спавне. /mgp setup assault " + m.id + " limbo <attackers|defenders>");
        hasExit = false;
        for (MapConfig.Trigger tr : m.triggers) for (String a : tr.actions()) if (isDeployAction(a)) hasExit = true;
        for (MapConfig.Script sc : m.scripts) for (String a : sc.actions()) if (isDeployAction(a)) hasExit = true;
    }

    private static boolean isDeployAction(String a) {
        String v = a.replace(" ", "").toLowerCase(Locale.ROOT);
        return v.startsWith("mode:deploy") || v.startsWith("mode:spawn");
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    private Stage stage() { return stageIdx >= 0 && stageIdx < stages.size() ? stages.get(stageIdx) : null; }

    /** Стадия, к которой относится цель (-1 — ни к какой: считается всегда). */
    private int stageOf(String objectiveId) {
        for (int i = 0; i < stages.size(); i++) if (stages.get(i).objectives().contains(objectiveId)) return i;
        return -1;
    }

    @Override
    public boolean objectiveActive(String id) {
        if (ended) return false;
        int st = stageOf(id);
        return st < 0 || st == stageIdx;
    }

    private MapConfig.Objective objectiveAt(Block b) {
        for (MapConfig.Objective o : session.map().objectives)
            if (o.region().contains(b.getX(), b.getY(), b.getZ())) return o;
        return null;
    }

    /** Текущий спавн команды: spawn-points этой команды с группой текущей стадии (или ближайшей предыдущей). */
    private Location activeSpawn(Team t) {
        MapConfig m = session.map();
        for (int i = Math.max(0, stageIdx); i >= 0 && i < stages.size(); i--) {
            String g = stages.get(i).spawnGroup();
            List<Pos> l = new ArrayList<>();
            for (MapConfig.SpawnPoint sp : m.spawnPoints)
                if (g.equals(sp.group()) && (sp.team() == null || sp.team().equals(t.id))) l.add(sp.pos());
            if (!l.isEmpty()) return l.get(session.random().nextInt(l.size())).toLocation(session.gameWorld());
        }
        return session.spawnFor(t.id);
    }

    // ------------------------------------------------------------------ старт

    private boolean inPrep() { return stageIdx < 0; }

    @Override
    public boolean explosivesAllowed() { return !inPrep() && !ended; }

    @Override
    public void onStart() {
        List<Player> all = session.alivePlayers();
        if (all.size() < 2 && !session.solo()) { session.end(List.of(), "<red>Для штурма нужно хотя бы 2 игрока."); return; }
        loadMap();
        Set<UUID> def = TeamSplit.defenders(session, all, defenderRatio);
        for (Player p : all) {
            teams.put(p.getUniqueId(), def.contains(p.getUniqueId()) ? Team.DEFENDERS : Team.ATTACKERS);
            money.put(p.getUniqueId(), moneyStart);
            PlayerUtil.reset(p, GameMode.SURVIVAL);
        }
        for (Player p : all) {
            Team t = team(p);
            Msg.title(p, t.color + "<bold>" + t.title.toUpperCase(Locale.ROOT),
                    t == Team.DEFENDERS ? "<gray>Не дай разрушить цели!" : "<gray>Разрушь цели защиты!");
            p.sendMessage(Msg.parse(t.color + "Ты в команде «" + t.title + "». <gray>" + (t == Team.DEFENDERS
                    ? "Стадий: " + stages.size() + ". Продержись, пока не выйдет время стадии — и цели устоят."
                    : "Стадий: " + stages.size() + ". Разрушь цели стадии (ТНТ, разрывные стрелы), пока не вышло время.")));
            p.sendMessage(Msg.parse("<gold>На счёте " + Shop.moneyName(moneyStart) + ". <gray>Монеты — за убийства, цели и оборону; тратятся в магазине лимбо."));
            equip(p);
            toLimbo(p, session.elapsed(), true);
        }
        if (prepTime > 0) session.broadcast("<yellow>Подготовка " + Msg.time(prepTime) + ": закупитесь в лимбо, драться и взрывать пока нельзя.");
        else startStage(0);
        session.plugin().visibility().refreshAll(session);
    }

    // ------------------------------------------------------------------ лимбо и выход на поле

    /** Отправить в лимбо (или сразу на поле, если лимбо нет). deployAt — с какой секунды можно выйти. */
    private void toLimbo(Player p, int deployAt, boolean teleport) {
        Team t = team(p);
        if (t == null) return;
        Location l = limbo.get(t);
        if (l == null) {
            if (teleport) p.teleport(activeSpawn(t));
            session.protectSpawn(p);
            return;
        }
        if (teleport) p.teleport(l);
        inLimbo.put(p.getUniqueId(), deployAt);
        var inv = p.getInventory();
        for (int slot : new int[]{7, 8}) { // освободить слоты под магазин и «В бой»
            ItemStack had = inv.getItem(slot);
            if (had != null && !had.getType().isAir() && Keys.tag(had) == null) { inv.setItem(slot, null); ShopService.give(p, had); }
        }
        if (shops.get(t) != null)
            inv.setItem(8, Items.tagged(Material.EMERALD, "<green><bold>Магазин</bold> <gray>(ПКМ)", "logic:" + SHOP_ITEM,
                    "<gray>Оружие, броня и наборы", "<gray>за монеты счёта"));
        if (!hasExit)
            inv.setItem(7, Items.tagged(Material.LIME_DYE, "<green><bold>В бой</bold> <gray>(ПКМ)", "logic:" + DEPLOY_ITEM,
                    "<gray>Выйти из лимбо на текущий спавн"));
    }

    private void removeLimboItems(Player p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            String tag = Keys.tag(inv.getItem(i));
            if (("logic:" + SHOP_ITEM).equals(tag) || ("logic:" + DEPLOY_ITEM).equals(tag)) inv.setItem(i, null);
        }
        if (p.getOpenInventory().getTopInventory().getHolder() instanceof ShopMenu) p.closeInventory();
    }

    /** Выход из лимбо на текущий спавн команды (действие mode: deploy). */
    private void deploy(Player p) {
        Integer at = inLimbo.get(p.getUniqueId());
        Team t = team(p);
        if (at == null || t == null || ended) return;
        int left = at - session.elapsed();
        if (left > 0) {
            p.sendActionBar(Msg.parse("<red>Выход через <yellow>" + left + " <red>с"));
            return;
        }
        inLimbo.remove(p.getUniqueId());
        removeLimboItems(p);
        Location to = activeSpawn(t);
        p.teleport(to);
        p.setFallDistance(0);
        p.setFireTicks(0);
        session.protectSpawn(p);
        Msg.title(p, t.color + "<bold>В БОЙ!", stage() == null ? "<gray>Бой начнётся после подготовки" : stage().name());
        p.playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.2f);
    }

    private void openShop(Player p) {
        Team t = team(p);
        String id = t == null ? null : shops.get(t);
        Shop shop = session.plugin().shops().get(id);
        if (shop == null) { p.sendMessage(Msg.parse("<red>Магазин " + Msg.esc(String.valueOf(id)) + " не найден (plugins/MGP/shops)")); return; }
        new ShopMenu(session.plugin().shops(), shop, 0).open(p);
    }

    // ------------------------------------------------------------------ снаряжение

    private void equip(Player p) {
        Team t = team(p);
        ru.mgp.config.Kit kit = session.equip(p, t.id);
        if (kit == null) for (ItemSpec s : t == Team.DEFENDERS ? defenderItems : attackerItems) giveSpec(p, s);
        // цветная кожаная куртка, если брони нет: видно своих; купленная броня её просто заменит
        ItemStack chest = p.getInventory().getChestplate();
        if (chest == null || chest.getType().isAir()) {
            ItemStack c = new ItemStack(Material.LEATHER_CHESTPLATE);
            if (c.getItemMeta() instanceof org.bukkit.inventory.meta.LeatherArmorMeta lm) {
                lm.setColor(Color.fromRGB(t.rgb));
                lm.setUnbreakable(true);
                c.setItemMeta(lm);
            }
            p.getInventory().setChestplate(Keys.tag(c, ShopService.TEAM_ARMOR));
        }
    }

    /** Предмет из defender-items / attacker-items: со slot — в этот слот, иначе как покупка (броня надевается). */
    private static void giveSpec(Player p, ItemSpec s) {
        ItemStack it = s.build();
        if (s.slot == null) { ShopService.give(p, it); return; }
        var inv = p.getInventory();
        switch (s.slot.toLowerCase(Locale.ROOT)) {
            case "head", "helmet" -> inv.setHelmet(it);
            case "chest", "chestplate" -> inv.setChestplate(it);
            case "legs", "leggings" -> inv.setLeggings(it);
            case "feet", "boots" -> inv.setBoots(it);
            case "offhand" -> inv.setItemInOffHand(it);
            default -> {
                try { inv.setItem(Integer.parseInt(s.slot), it); } catch (NumberFormatException e) { inv.addItem(it); }
            }
        }
    }

    // ------------------------------------------------------------------ экономика

    @Override
    public int balance(Player p) {
        Integer m = money.get(p.getUniqueId());
        return m == null ? -1 : m;
    }

    @Override
    public boolean spend(Player p, int amount) {
        Integer m = money.get(p.getUniqueId());
        if (m == null || m < amount) return false;
        money.put(p.getUniqueId(), m - amount);
        return true;
    }

    private void reward(Player p, int amount, String why) {
        if (p == null || amount == 0 || !money.containsKey(p.getUniqueId())) return;
        int now = money.get(p.getUniqueId());
        int next = Math.max(0, moneyMax > 0 ? Math.min(moneyMax, now + amount) : now + amount);
        money.put(p.getUniqueId(), next);
        if (next != now) p.sendMessage(Msg.parse("<gold>" + (amount > 0 ? "+" : "") + Shop.moneyName(next - now) + " <gray>" + why
                + (moneyMax > 0 && next == moneyMax ? " <dark_gray>(максимум)" : "")));
    }

    private void rewardTeam(Team t, int amount, String why) {
        if (amount == 0) return;
        for (Player p : members(t)) reward(p, amount, why);
    }

    /** Расстояние от точки до ближайшей активной цели (∞ — целей нет). */
    private double distanceToObjective(Location l) {
        double best = Double.MAX_VALUE;
        Stage st = stage();
        if (st == null) return best;
        for (MapConfig.Objective o : session.map().objectives) {
            if (!st.objectives().contains(o.id()) || session.objectives().isDone(o.id())) continue;
            var r = o.region();
            double dx = Math.max(Math.max(r.minX() - l.getX(), l.getX() - (r.maxX() + 1)), 0);
            double dy = Math.max(Math.max(r.minY() - l.getY(), l.getY() - (r.maxY() + 1)), 0);
            double dz = Math.max(Math.max(r.minZ() - l.getZ(), l.getZ() - (r.maxZ() + 1)), 0);
            best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return best;
    }

    // ------------------------------------------------------------------ стадии

    private void startStage(int i) {
        stageIdx = i;
        stageStartAt = session.elapsed();
        Stage st = stages.get(i);
        List<String> names = new ArrayList<>();
        for (String id : st.objectives())
            for (MapConfig.Objective o : session.map().objectives) if (o.id().equals(id)) names.add(o.name());
        for (Player p : session.online()) {
            Msg.title(p, "<gold><bold>СТАДИЯ " + (i + 1) + "/" + stages.size(), st.name());
            p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 1f, 1f);
        }
        session.broadcast("<gold><bold>Стадия " + (i + 1) + "/" + stages.size() + ":</bold> " + st.name() + " <gray>— " + Msg.time(st.time())
                + (names.isEmpty() ? "" : ". Цели: " + String.join("<gray>, ", names)
                + (st.require() > 0 && st.require() < names.size() ? " <gray>(нужно " + st.require() + ")" : "")));
        ActionRunner.run(session, st.actions(), null);
        if (i > 0 && !redeploy.equals("none")) {
            for (Player p : session.alivePlayers()) {
                Team t = team(p);
                if (t == null || inLimbo.containsKey(p.getUniqueId())) continue;
                if (redeploy.equals("all") || redeploy.equals(t.id)) {
                    p.teleport(activeSpawn(t));
                    session.protectSpawn(p);
                }
            }
        }
        checkStage(); // цели стадии могли быть разрушены заранее
    }

    private void checkStage() {
        Stage st = stage();
        if (st == null || ended || !session.isPlaying()) return;
        List<String> present = new ArrayList<>();
        for (String id : st.objectives()) if (session.objectives().has(id)) present.add(id);
        if (present.isEmpty()) return;
        int done = 0;
        for (String id : present) if (session.objectives().isDone(id)) done++;
        int need = st.require() <= 0 ? present.size() : Math.min(st.require(), present.size());
        if (done >= need) stageTaken();
    }

    private void stageTaken() {
        if (stageIdx >= stages.size() - 1) {
            win(Team.ATTACKERS, "<red>Атакующие разрушили все цели!");
            return;
        }
        session.broadcast("<red><bold>Стадия " + (stageIdx + 1) + " взята!</bold> <gray>Защита отступает, спавны сдвигаются.");
        rewardTeam(Team.ATTACKERS, rewardStageAttackers, "за взятую стадию");
        rewardTeam(Team.DEFENDERS, rewardStageDefenders, "на перегруппировку");
        startStage(stageIdx + 1);
    }

    @Override
    public void onObjectiveDestroyed(MapConfig.Objective o) {
        if (ended) return;
        Set<UUID> who = contributors.getOrDefault(o.id(), Set.of());
        for (Player p : members(Team.ATTACKERS)) {
            if (who.contains(p.getUniqueId())) reward(p, rewardObjective, "за разрушение цели");
            reward(p, rewardObjectiveTeam, "команде за цель");
        }
        checkStage();
    }

    private void win(Team t, String reason) {
        if (ended) return;
        ended = true;
        List<UUID> w = new ArrayList<>();
        for (Map.Entry<UUID, Team> e : teams.entrySet()) if (e.getValue() == t && Bukkit.getPlayer(e.getKey()) != null) w.add(e.getKey());
        session.end(w, reason);
    }

    // ------------------------------------------------------------------ блоки

    @Override
    public void onBlockBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        if (inLimbo.containsKey(p.getUniqueId())) { e.setCancelled(true); return; }
        long k = key(b);
        if (placed.remove(k)) return; // поставлен за игру — можно
        MapConfig.Objective o = objectiveAt(b);
        if (o != null) {
            Team t = team(p);
            if (t == Team.ATTACKERS && objectiveHandBreak && objectiveActive(o.id()) && !inPrep()) {
                contributors.computeIfAbsent(o.id(), x -> new HashSet<>()).add(p.getUniqueId());
                return;
            }
            e.setCancelled(true);
            p.sendActionBar(Msg.parse(t == Team.DEFENDERS ? "<red>Свою цель ломать нельзя"
                    : !objectiveActive(o.id()) || inPrep() ? "<red>Эта цель пока недоступна — сначала текущая стадия"
                    : "<red>Цель руками не сломать — нужен ТНТ или разрывные стрелы"));
            return;
        }
        if (breakable.contains(b.getType())) return;
        e.setCancelled(true);
        p.sendActionBar(Msg.parse("<red>Этот блок не ломается"));
    }

    @Override
    public void onBlockPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlockPlaced();
        Team t = team(p);
        if (inLimbo.containsKey(p.getUniqueId())) { e.setCancelled(true); return; }
        if (b.getType() == Material.TNT) {
            if (inPrep()) { e.setCancelled(true); p.sendActionBar(Msg.parse("<red>ТНТ — только после подготовки")); return; }
            if (t == Team.DEFENDERS && !defenderTnt) { e.setCancelled(true); p.sendActionBar(Msg.parse("<red>Защитникам ТНТ не положен")); return; }
            if (autoTnt) {
                e.setCancelled(true);
                ItemStack hand = e.getItemInHand();
                hand.setAmount(hand.getAmount() - 1);
                p.getInventory().setItem(e.getHand(), hand.getAmount() <= 0 ? null : hand);
                Location at = b.getLocation().add(0.5, 0, 0.5);
                b.getWorld().spawn(at, TNTPrimed.class, tnt -> { tnt.setFuseTicks(tntFuse); tnt.setSource(p); });
                b.getWorld().playSound(at, Sound.ENTITY_TNT_PRIMED, 1f, 1f);
                return;
            }
        }
        if (t == Team.DEFENDERS && !defendersRepair && objectiveAt(b) != null) {
            e.setCancelled(true);
            p.sendActionBar(Msg.parse("<red>Цель нельзя заделывать блоками"));
            return;
        }
        placed.add(key(b));
    }

    @Override
    public void onBucket(PlayerBucketEmptyEvent e) { e.setCancelled(true); }

    @Override
    public void onExplode(EntityExplodeEvent e) {
        Entity en = e.getEntity();
        Player src = en instanceof Player pl ? pl : en instanceof TNTPrimed tnt && tnt.getSource() instanceof Player pl ? pl : null;
        blocks(e.blockList(), src);
        e.setYield(0f);
    }

    @Override
    public void onBlockExplode(BlockExplodeEvent e) {
        blocks(e.blockList(), null);
        e.setYield(0f);
    }

    /** Что может сломать взрыв: не explosion-proof, цели — только текущей стадии и не от взрыва защитников. */
    private void blocks(List<Block> list, Player src) {
        Team st = src == null ? null : team(src);
        list.removeIf(b -> {
            if (explosionProof.contains(b.getType())) return true;
            MapConfig.Objective o = objectiveAt(b);
            if (o == null) return false;
            if (!objectiveActive(o.id()) || inPrep() || st == Team.DEFENDERS) return true;
            if (src != null && b.getType().isSolid()) contributors.computeIfAbsent(o.id(), x -> new HashSet<>()).add(src.getUniqueId());
            return false;
        });
        for (Block b : list) placed.remove(key(b));
    }

    private static long key(Block b) {
        return ((long) (b.getX() & 0x3FFFFFF) << 38) | ((long) (b.getZ() & 0x3FFFFFF) << 12) | (b.getY() & 0xFFF);
    }

    @Override
    public void onInteract(PlayerInteractEvent e) {
        // защитники не поджигают ТНТ (если defender-tnt: false)
        if (!defenderTnt && e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getHand() == EquipmentSlot.HAND && e.getItem() != null
                && e.getItem().getType() == Material.TNT && team(e.getPlayer()) == Team.DEFENDERS) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(Msg.parse("<red>Защитникам ТНТ не положен"));
        }
    }

    // ------------------------------------------------------------------ урон и смерть

    private static Player damager(EntityDamageEvent e) {
        if (!(e instanceof EntityDamageByEntityEvent ee)) return null;
        Entity d = ee.getDamager();
        if (d instanceof Player p) return p;
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        if (d instanceof TNTPrimed t && t.getSource() instanceof Player p) return p;
        return null;
    }

    @Override
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        UUID v = victim.getUniqueId();
        if (inLimbo.containsKey(v) || ended) { e.setCancelled(true); return; }
        Player a = damager(e);
        if (a != null && !a.equals(victim)) {
            if (inLimbo.containsKey(a.getUniqueId()) || team(a) == team(victim) || inPrep()) { e.setCancelled(true); return; }
        }
        if (a != null && a.equals(victim) && e.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) e.setDamage(e.getDamage() * 0.3);
    }

    @Override
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        e.deathMessage(null);
        Player killer = p.getKiller();
        Team t = team(p);
        Team kt = killer == null ? null : team(killer);
        String who = (t == null ? "<white>" : t.color) + Msg.esc(p.getName());
        if (killer != null && !killer.equals(p) && kt != null && kt != t) {
            session.addKill(killer.getUniqueId());
            killer.playSound(killer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            reward(killer, rewardKill, "за убийство " + Msg.esc(p.getName()));
            if (kt == Team.DEFENDERS && defenseRadius > 0
                    && (distanceToObjective(p.getLocation()) <= defenseRadius || distanceToObjective(killer.getLocation()) <= defenseRadius))
                reward(killer, rewardDefense, "за оборону цели");
            session.broadcast(who + " <gray>убит игроком " + kt.color + Msg.esc(killer.getName()));
        } else session.broadcast(who + " <gray>погиб");
        if (keepInventory) {
            e.setKeepInventory(true);
            e.getDrops().clear();
            e.setKeepLevel(true);
        } else if (!dropItems) e.getDrops().clear();
        else e.getDrops().removeIf(it -> Keys.tag(it) != null);
        e.setDroppedExp(0);
        inLimbo.remove(p.getUniqueId());
    }

    @Override
    public Location respawnLocation(Player p) {
        Team t = team(p);
        if (t == null || ended) return null;
        Location l = limbo.get(t);
        int at = session.elapsed() + respawnDelay;
        if (l != null) inLimbo.put(p.getUniqueId(), at); // сразу: зона карты не держит игрока в лимбо
        Bukkit.getScheduler().runTask(session.plugin(), () -> {
            if (!p.isOnline() || !session.isPlaying() || team(p) == null) return;
            p.setGameMode(GameMode.SURVIVAL);
            if (!keepInventory) { p.getInventory().clear(); equip(p); }
            toLimbo(p, at, false);
            if (l != null) p.sendMessage(Msg.parse("<gray>Ты в лимбо: <green>магазин</green> — "
                    + (shops.get(t) != null ? "изумруд в хотбаре" : "продавцы") + ", выход — " + (hasExit ? "проход из лимбо" : "кнопка «В бой»")
                    + ". <gold>На счёте " + Shop.moneyName(money.getOrDefault(p.getUniqueId(), 0))));
        });
        return l != null ? l : activeSpawn(t);
    }

    @Override
    public void onLeave(Player p, boolean wasAlive) {
        inLimbo.remove(p.getUniqueId());
        BossBar bar = bars.remove(p.getUniqueId());
        if (bar != null) p.hideBossBar(bar);
        if (!wasAlive || ended || !session.isPlaying()) return;
        Team t = team(p);
        session.broadcast((t == null ? "<white>" : t.color) + Msg.esc(p.getName()) + " <gray>покинул игру.");
        Bukkit.getScheduler().runTask(session.plugin(), () -> {
            if (ended || !session.isPlaying()) return;
            if (session.solo()) { // отладка в одиночку: пустая команда не означает победу
                if (session.aliveIds().isEmpty()) session.end(List.of(), null);
                return;
            }
            if (members(Team.DEFENDERS).isEmpty()) win(Team.ATTACKERS, "<red>Защитников не осталось!");
            else if (members(Team.ATTACKERS).isEmpty()) win(Team.DEFENDERS, "<blue>Атакующих не осталось!");
        });
    }

    // ------------------------------------------------------------------ хотбар и действия

    @Override
    public void onHotbar(Player p, String payload) {
        if (payload.equals(SHOP_ITEM)) { if (inLimbo.containsKey(p.getUniqueId())) openShop(p); }
        else if (payload.equals(DEPLOY_ITEM)) deploy(p);
    }

    @Override
    public boolean handleAction(String action, Player target) {
        String[] a = action.trim().split("\\s+");
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "deploy", "spawn" -> {
                if (target != null) deploy(target);
                else for (Player p : session.alivePlayers()) deploy(p);
                return true;
            }
            case "shop" -> { if (target != null) openShop(target); return true; }
            case "money" -> { // mode: money <сумма> — монеты игроку (или всем)
                int n = a.length > 1 ? parseInt(a[1]) : 0;
                if (target != null) reward(target, n, "");
                else for (Player p : session.alivePlayers()) reward(p, n, "");
                return true;
            }
            case "next-stage" -> { if (stage() != null) stageTaken(); return true; }
            case "attackers-win" -> { win(Team.ATTACKERS, "<red>Атакующие победили!"); return true; }
            case "defenders-win" -> { win(Team.DEFENDERS, "<blue>Защитники победили!"); return true; }
            default -> { return false; }
        }
    }

    // ------------------------------------------------------------------ тик, панель, цвета

    @Override
    public void onTick() {
        if (ended) return;
        int now = session.elapsed();
        if (inPrep() && now >= prepTime) {
            session.broadcast("<red><bold>Подготовка окончена — штурм начался!");
            startStage(0);
            if (ended) return;
        }
        if (income != 0 && incomeEvery > 0 && now > 0 && now % incomeEvery == 0)
            for (Player p : session.alivePlayers()) reward(p, income, "доход");
        for (Map.Entry<UUID, Integer> en : new ArrayList<>(inLimbo.entrySet())) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null) { inLimbo.remove(en.getKey()); continue; }
            int left = en.getValue() - now;
            if (limboTimeout > 0 && now - (en.getValue() - respawnDelay) >= limboTimeout && left <= 0) { deploy(p); continue; }
            p.sendActionBar(Msg.parse("<gold>" + Shop.moneyName(money.getOrDefault(p.getUniqueId(), 0)) + " <dark_gray>| "
                    + (left > 0 ? "<gray>выход через <yellow>" + left + " <gray>с" : "<green>можно в бой — " + (hasExit ? "иди к выходу" : "кнопка «В бой»"))));
        }
        Stage st = stage();
        int left = st == null ? prepTime - now : stageStartAt + st.time() - now;
        if (st != null && left <= 0) {
            win(Team.DEFENDERS, "<blue>Время стадии вышло — защита выстояла!");
            return;
        }
        String title = st == null ? "<yellow>Подготовка <white>" + Msg.time(Math.max(0, left))
                : "<gold>Стадия " + (stageIdx + 1) + "/" + stages.size() + " <gray>— " + st.name() + " <white>" + Msg.time(Math.max(0, left));
        int total = st == null ? Math.max(1, prepTime) : Math.max(1, st.time());
        float prog = Math.max(0f, Math.min(1f, left / (float) total));
        for (Player p : session.online()) {
            BossBar bar = bars.get(p.getUniqueId());
            if (bar == null) {
                bar = BossBar.bossBar(Msg.parse(title), prog, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
                bars.put(p.getUniqueId(), bar);
                p.showBossBar(bar);
            } else { bar.name(Msg.parse(title)); bar.progress(prog); }
        }
    }

    @Override
    public void onTimeUp() {} // время считают стадии (stage-time / time в стадиях карты)

    @Override
    public boolean customZones() {
        for (Stage st : stages) if (!st.zones().isEmpty()) return true;
        return false;
    }

    /**
     * Зона команды на текущей стадии (в подготовку — как в стадии 1). stage-zones: cumulative — открытые зоны
     * всех стадий до текущей вместе (атака продвигается дальше, тыл остаётся); replace — только зона последней стадии, где она задана.
     */
    @Override
    public List<Region> stageZone(Player p) {
        Team t = team(p);
        if (t == null || ended || stages.isEmpty()) return null;
        int cur = Math.max(0, Math.min(stageIdx, stages.size() - 1));
        boolean cumulative = !"replace".equalsIgnoreCase(session.mode.settings.getString("stage-zones", "cumulative"));
        boolean any = false;
        for (Stage st : stages) if (st.zones().containsKey(t.id)) { any = true; break; }
        if (!any) return null; // у команды нет зон стадий — только play-зона и стартовая зона (teamzone)
        List<Region> out = new ArrayList<>();
        for (int i = cur; i >= 0; i--) {
            List<Region> z = new ArrayList<>();
            List<Region> own = stages.get(i).zones().get(t.id);
            if (own != null) z.addAll(own);
            // стартовая зона команды (/mgp setup teamzone, например база атаки) всегда входит в стадию 1
            List<Region> base = i == 0 ? session.map().teamZones.get(t.id) : null;
            if (base != null) z.addAll(base);
            if (z.isEmpty()) continue;
            out.addAll(z);
            if (!cumulative) break;
        }
        return out.isEmpty() ? null : out;
    }

    @Override
    public boolean zoneFree(Player p) { return inLimbo.containsKey(p.getUniqueId()); }

    @Override
    public int teamZonesOpenAt() { return prepTime > 0 ? prepTime : -1; }

    @Override
    public boolean kitsInLobby() { return false; }

    @Override
    public boolean nametagVisible(Player viewer, Player target) {
        if (!hideEnemyNames) return true;
        Team a = team(viewer), b = team(target);
        if (a == null || b == null || a == b) return true; // свои — всегда
        // противник — только пока смотришь прямо на него и между вами нет стены
        return enemyNameOnAim && session.plugin().sidebars().aimingAt(viewer, target);
    }

    @Override
    public boolean aimNametags() { return hideEnemyNames && enemyNameOnAim; }

    @Override
    public void placeholders(Player p, Map<String, String> ph) {
        Team t = team(p);
        ph.put("team", t == null ? "<gray>-" : t.color + t.title);
        Stage st = stage();
        int now = session.elapsed();
        ph.put("stage", st == null ? "-" : (stageIdx + 1) + "/" + stages.size());
        ph.put("stage_name", st == null ? "<yellow>Подготовка" : st.name());
        ph.put("phase", st == null ? "До штурма" : "До конца стадии");
        ph.put("time", Msg.time(Math.max(0, st == null ? prepTime - now : stageStartAt + st.time() - now)));
        ph.put("money", String.valueOf(money.getOrDefault(p.getUniqueId(), 0)));
        int total = 0, done = 0;
        StringBuilder prog = new StringBuilder();
        if (st != null) for (String id : st.objectives()) {
            if (!session.objectives().has(id)) continue;
            total++;
            if (session.objectives().isDone(id)) done++;
            int pc = session.objectives().progressOf(id);
            if (prog.length() > 0) prog.append(" ");
            prog.append(pc >= 100 ? "<green>✔" : "<white>" + pc + "%");
        }
        ph.put("objectives", total == 0 ? "-" : done + "/" + total);
        ph.put("objectives_progress", prog.length() == 0 ? "-" : prog.toString());
        ph.put("attackers", String.valueOf(members(Team.ATTACKERS).size()));
        ph.put("defenders", String.valueOf(members(Team.DEFENDERS).size()));
        ph.put("limbo", inLimbo.containsKey(p.getUniqueId()) ? "<green>лимбо" : "<gray>на поле");
    }

    @Override
    public Map<String, net.kyori.adventure.text.format.NamedTextColor> teamColors() {
        Map<String, net.kyori.adventure.text.format.NamedTextColor> m = new HashMap<>();
        for (Map.Entry<UUID, Team> e : teams.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) m.put(p.getName(), e.getValue().named);
        }
        return m;
    }

    @Override
    public void onEnd() {
        ended = true;
        for (UUID u : new ArrayList<>(bars.keySet())) {
            Player p = Bukkit.getPlayer(u);
            BossBar bar = bars.remove(u);
            if (p != null && bar != null) p.hideBossBar(bar);
        }
        for (UUID u : inLimbo.keySet()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) removeLimboItems(p);
        }
        inLimbo.clear();
    }
}
