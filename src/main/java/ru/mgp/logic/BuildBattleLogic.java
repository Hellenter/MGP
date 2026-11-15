package ru.mgp.logic;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import ru.mgp.config.MapConfig;
import ru.mgp.session.GameSession;
import ru.mgp.util.Cfg;
import ru.mgp.util.Items;
import ru.mgp.util.Msg;
import ru.mgp.util.PlayerUtil;
import ru.mgp.vote.Vote;
import ru.mgp.vote.VoteType;

import java.util.*;

/**
 * Билд баттл: голосование за тему в лобби → строительство на своём участке →
 * обход всех участков с оценками → итоги.
 */
public final class BuildBattleLogic extends GameLogic {
    private enum Phase { BUILD, JUDGING, RESULTS }

    private record Grade(String name, Material material, int points) {}

    private final int buildTime;
    private final int judgeTime;
    private final int plotMargin;
    private final List<String> themes;
    private final int themeOptions;
    private final VoteType themeVote;
    private final Set<Material> banned = EnumSet.noneOf(Material.class);
    private final List<Grade> grades = new ArrayList<>();

    private Phase phase;
    private int phaseLeft;
    private String theme = "?";
    private final Map<UUID, MapConfig.Plot> plotOf = new LinkedHashMap<>();
    private final List<UUID> judgeOrder = new ArrayList<>();
    private int judgeIndex;
    private final Map<UUID, Map<UUID, Integer>> scores = new HashMap<>();

    public BuildBattleLogic(GameSession s) {
        super(s);
        var st = s.mode.settings;
        buildTime = Cfg.seconds(st.get("build-time"), 300);
        judgeTime = st.getInt("judge-time", 15);
        plotMargin = st.getInt("plot-margin", 3);
        themes = st.getStringList("themes");
        themeOptions = st.getInt("theme-options", 5);
        themeVote = Cfg.enumValue(VoteType.class, st.getString("theme-vote"), VoteType.MOST_VOTES);
        for (String m : st.getStringList("banned-items")) {
            Material mat = Cfg.material(m, null);
            if (mat != null) banned.add(mat);
        }
        for (Map<?, ?> raw : st.getMapList("grades")) {
            Map<String, Object> m = Cfg.asMap(raw);
            grades.add(new Grade(Cfg.str(m, "name", "?"), Cfg.material(Cfg.str(m, "material", null), Material.WHITE_WOOL),
                    Cfg.integer(m, "points", 0)));
        }
        if (grades.isEmpty()) {
            grades.add(new Grade("<dark_red>Ужасно", Material.RED_TERRACOTTA, 1));
            grades.add(new Grade("<red>Плохо", Material.ORANGE_TERRACOTTA, 2));
            grades.add(new Grade("<yellow>Нормально", Material.YELLOW_TERRACOTTA, 3));
            grades.add(new Grade("<green>Хорошо", Material.LIME_TERRACOTTA, 4));
            grades.add(new Grade("<dark_green>Отлично", Material.GREEN_TERRACOTTA, 5));
            grades.add(new Grade("<gold><bold>Шедевр", Material.GOLD_BLOCK, 6));
        }
    }

    @Override
    public void registerVotes() {
        if (themes.isEmpty()) return;
        List<String> pool = new ArrayList<>(themes);
        Collections.shuffle(pool, session.random());
        if (themeOptions > 0 && pool.size() > themeOptions) pool = pool.subList(0, themeOptions);
        List<Vote.Option> opts = new ArrayList<>();
        for (String t : pool) opts.add(new Vote.Option(t, "<yellow>" + t, Material.NAME_TAG, 1.0, List.of()));
        double base = session.plugin().getConfig().getDouble("voting.weighted.base", 1.0);
        double per = session.plugin().getConfig().getDouble("voting.weighted.per-vote", 1.0);
        session.addVote(new Vote("theme", "<light_purple>Тема", Material.NAME_TAG, themeVote, opts, base, per));
    }

    @Override
    public void onVotesClosed() {
        Vote v = session.votes().get("theme");
        String t = v != null ? v.resolve(session.random()) : null;
        if (t != null) theme = t;
        session.broadcast("<light_purple>Тема: <yellow>" + Msg.esc(theme));
    }

    @Override
    public void onStart() {
        List<MapConfig.Plot> plots = new ArrayList<>(session.map().plots);
        if (plots.isEmpty()) {
            session.end(List.of(), "<red>На карте нет участков!");
            return;
        }
        Collections.shuffle(plots, session.random());
        List<Player> players = session.alivePlayers();
        Collections.shuffle(players, session.random());
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            if (i >= plots.size()) {
                Msg.send(p, "<red>Не хватило участков, ты наблюдаешь.");
                session.eliminate(p);
                continue;
            }
            MapConfig.Plot plot = plots.get(i);
            plotOf.put(p.getUniqueId(), plot);
            PlayerUtil.reset(p, GameMode.CREATIVE);
            p.teleport(plot.spawn().toLocation(session.gameWorld()));
            session.equip(p);
            Msg.title(p, "<light_purple>Тема: <yellow>" + Msg.esc(theme), "<gray>У тебя " + Msg.time(buildTime));
        }
        phase = Phase.BUILD;
        phaseLeft = buildTime;
    }

    @Override
    public void onTick() {
        if (phase == null || phase == Phase.RESULTS) return;
        phaseLeft--;
        if (phase == Phase.BUILD) {
            if (phaseLeft == 60 || phaseLeft == 30 || phaseLeft == 10 || (phaseLeft <= 5 && phaseLeft > 0)) {
                session.broadcast("<yellow>До конца строительства: <red>" + Msg.time(phaseLeft));
            }
            if (phaseLeft <= 0) startJudging();
        } else if (phase == Phase.JUDGING && phaseLeft <= 0) {
            nextPlot();
        }
    }

    private void startJudging() {
        phase = Phase.JUDGING;
        judgeOrder.clear();
        for (UUID u : plotOf.keySet()) if (session.playerIds().contains(u)) judgeOrder.add(u);
        Collections.shuffle(judgeOrder, session.random());
        judgeIndex = -1;
        session.broadcast("<gold><bold>Время вышло!</bold> <yellow>Начинаем оценку построек.");
        nextPlot();
    }

    private void nextPlot() {
        judgeIndex++;
        if (judgeIndex >= judgeOrder.size()) {
            results();
            return;
        }
        phaseLeft = judgeTime;
        MapConfig.Plot plot = plotOf.get(judgeOrder.get(judgeIndex));
        Location loc = plot.spawn().toLocation(session.gameWorld());
        for (Player p : session.alivePlayers()) {
            PlayerUtil.reset(p, GameMode.ADVENTURE);
            p.setAllowFlight(true);
            p.setFlying(true);
            p.teleport(loc);
            for (int i = 0; i < grades.size() && i < 9; i++) {
                Grade g = grades.get(i);
                p.getInventory().setItem(i, Items.tagged(g.material(), g.name(), "logic:vote:" + i));
            }
            Msg.title(p, "<yellow>Оцени постройку!", "<gray>Тема: " + Msg.esc(theme));
        }
    }

    @Override
    public void onHotbar(Player p, String payload) {
        if (phase != Phase.JUDGING || !payload.startsWith("vote:")) return;
        UUID owner = judgeOrder.get(judgeIndex);
        if (owner.equals(p.getUniqueId())) {
            Msg.send(p, "<red>Нельзя оценивать свою постройку.");
            return;
        }
        int idx = Integer.parseInt(payload.substring(5));
        Grade g = grades.get(idx);
        scores.computeIfAbsent(owner, k -> new HashMap<>()).put(p.getUniqueId(), g.points());
        p.sendActionBar(Msg.parse("<gray>Твоя оценка: </gray>" + g.name()));
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
    }

    private void results() {
        phase = Phase.RESULTS;
        List<UUID> order = new ArrayList<>(judgeOrder);
        order.sort(Comparator.comparingInt(this::total).reversed());
        session.broadcast("<gold><bold>Итоги:");
        for (int i = 0; i < Math.min(3, order.size()); i++) {
            Player p = org.bukkit.Bukkit.getPlayer(order.get(i));
            String name = p != null ? Msg.esc(p.getName()) : "?";
            session.broadcast("<yellow>#" + (i + 1) + " <white>" + name + " <gray>— " + total(order.get(i)) + " очк.");
        }
        if (order.isEmpty()) {
            session.end(List.of(), null);
            return;
        }
        UUID winner = order.get(0);
        Location loc = plotOf.get(winner).spawn().toLocation(session.gameWorld());
        for (Player p : session.alivePlayers()) {
            p.getInventory().clear();
            p.teleport(loc);
        }
        session.end(List.of(winner), null);
    }

    private int total(UUID owner) {
        int sum = 0;
        for (int v : scores.getOrDefault(owner, Map.of()).values()) sum += v;
        return sum;
    }

    // ---------------------------------------------------------------- защита участков

    private boolean canBuildAt(Player p, Location l) {
        if (phase != Phase.BUILD) return false;
        MapConfig.Plot plot = plotOf.get(p.getUniqueId());
        return plot != null && plot.region().contains(l);
    }

    @Override
    public void onBlockPlace(BlockPlaceEvent e) {
        if (!canBuildAt(e.getPlayer(), e.getBlock().getLocation()) || banned.contains(e.getBlock().getType()))
            e.setCancelled(true);
    }

    @Override
    public void onBlockBreak(BlockBreakEvent e) {
        if (!canBuildAt(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @Override
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (!canBuildAt(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @Override
    public void onInteract(PlayerInteractEvent e) {
        if (e.getItem() != null && banned.contains(e.getItem().getType())) e.setCancelled(true);
    }

    @Override
    public void onFlow(BlockFromToEvent e) {
        for (MapConfig.Plot plot : plotOf.values()) {
            if (plot.region().contains(e.getBlock().getLocation())) {
                if (!plot.region().contains(e.getToBlock().getLocation())) e.setCancelled(true);
                return;
            }
        }
        e.setCancelled(true);
    }

    @Override
    public void onCreatureSpawn(CreatureSpawnEvent e) {
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.SPAWNER_EGG) {
            e.setCancelled(true);
            return;
        }
        for (MapConfig.Plot plot : plotOf.values()) if (plot.region().contains(e.getLocation())) return;
        e.setCancelled(true);
    }

    @Override
    public void onDamage(EntityDamageEvent e) {
        e.setCancelled(true);
    }

    @Override
    public void onMove(PlayerMoveEvent e) {
        if (phase != Phase.BUILD) return;
        MapConfig.Plot plot = plotOf.get(e.getPlayer().getUniqueId());
        if (plot == null) return;
        if (!plot.region().containsLoose(e.getTo(), plotMargin)) {
            e.setTo(plot.spawn().toLocation(session.gameWorld()));
            e.getPlayer().sendActionBar(Msg.parse("<red>Нельзя покидать свой участок"));
        }
    }

    @Override
    public void onLeave(Player p, boolean wasAlive) {
        if (session.aliveIds().isEmpty()) session.end(List.of(), null);
    }

    @Override
    public void onTimeUp() {
        // У билд баттла свои таймеры фаз; общий time-limit не используется.
    }

    @Override
    public void placeholders(Player p, Map<String, String> ph) {
        ph.put("theme", Msg.esc(theme));
        ph.put("phase", phase == null ? "-" : switch (phase) {
            case BUILD -> "Строительство";
            case JUDGING -> "Оценка";
            case RESULTS -> "Итоги";
        });
        ph.put("phase_time", Msg.time(phaseLeft));
        ph.put("plot", phase == Phase.JUDGING ? (judgeIndex + 1) + "/" + judgeOrder.size() : "-");
    }

    @Override
    public String sidebarGroup() {
        if (phase == Phase.BUILD) return "build";
        if (phase == Phase.JUDGING) return "judging";
        return null;
    }
}
