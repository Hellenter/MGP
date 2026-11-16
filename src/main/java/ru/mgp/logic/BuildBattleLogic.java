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
import ru.mgp.util.Keys;
import ru.mgp.util.Msg;
import ru.mgp.util.PlayerUtil;
import ru.mgp.vote.Vote;
import ru.mgp.vote.VoteType;

import java.util.*;

/**
 * Билд баттл: голосование за тему в лобби → строительство на своём участке (стены между участками) →
 * время вышло: стены исчезают, все в центре арены смотрят на постройки → обход участков с анонимной
 * оценкой (6 блоков от красного к зелёному) → итоги: авторы и очки над каждой постройкой.
 */
public final class BuildBattleLogic extends GameLogic {
    private enum Phase { BUILD, REVEAL, JUDGING, RESULTS }

    private record Grade(String name, Material material, int points) {}

    private final int buildTime;
    private final int judgeTime;
    private final int revealTime;
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
        revealTime = Math.max(0, st.getInt("reveal-time", 10));
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
            // градиент от красного к зелёному
            grades.add(new Grade("<dark_red>Ужасно", Material.RED_CONCRETE, 1));
            grades.add(new Grade("<red>Плохо", Material.ORANGE_CONCRETE, 2));
            grades.add(new Grade("<gold>Так себе", Material.YELLOW_CONCRETE, 3));
            grades.add(new Grade("<yellow>Нормально", Material.LIME_TERRACOTTA, 4));
            grades.add(new Grade("<green>Хорошо", Material.LIME_CONCRETE, 5));
            grades.add(new Grade("<dark_green><bold>Отлично", Material.GREEN_CONCRETE, 6));
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
            if (phaseLeft <= 0) reveal();
        } else if (phase == Phase.REVEAL && phaseLeft <= 0) {
            startJudging();
        } else if (phase == Phase.JUDGING && phaseLeft <= 0) {
            nextPlot();
        }
    }

    /** Время стройки вышло: стены между участками исчезают, все в центре арены. */
    private void reveal() {
        phase = Phase.REVEAL;
        phaseLeft = revealTime;
        removeWalls();
        Location arena = arena();
        for (Player p : session.alivePlayers()) {
            PlayerUtil.reset(p, GameMode.ADVENTURE);
            p.setAllowFlight(true);
            if (arena != null) p.teleport(arena);
            p.setFlying(true);
            Msg.title(p, "<gold><bold>Время вышло!", "<yellow>Посмотри на все постройки");
            p.playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.2f);
        }
        session.broadcast("<gold><bold>Время вышло!</bold> <yellow>Стены пали — смотрите, что построили. Скоро оценка.");
        if (revealTime == 0) startJudging();
    }

    /** Убирает блоки стен (map.walls) в игровом мире. */
    private void removeWalls() {
        var w = session.gameWorld();
        if (w == null) return;
        int removed = 0;
        for (ru.mgp.util.Region r : session.map().walls) {
            long vol = (long) (r.maxX() - r.minX() + 1) * (r.maxY() - r.minY() + 1) * (r.maxZ() - r.minZ() + 1);
            if (vol > 2_000_000) {
                session.plugin().getLogger().warning("Стена на карте " + session.map().id + " больше 2 млн блоков, пропускаю");
                continue;
            }
            for (int y = r.minY(); y <= r.maxY(); y++)
                for (int z = r.minZ(); z <= r.maxZ(); z++)
                    for (int x = r.minX(); x <= r.maxX(); x++) {
                        var b = w.getBlockAt(x, y, z);
                        if (!b.getType().isAir()) { b.setType(Material.AIR, false); removed++; }
                    }
            Location c = new Location(w, (r.minX() + r.maxX() + 1) / 2.0, (r.minY() + r.maxY() + 1) / 2.0, (r.minZ() + r.maxZ() + 1) / 2.0);
            w.spawnParticle(org.bukkit.Particle.CLOUD, c, 60, (r.maxX() - r.minX()) / 2.0, (r.maxY() - r.minY()) / 2.0, (r.maxZ() - r.minZ()) / 2.0, 0.02);
        }
        if (removed > 0) for (Player p : session.alivePlayers()) p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.3f);
    }

    /** Центр арены: arena → spectator → середина всех участков над самым высоким. */
    private Location arena() {
        var m = session.map();
        var w = session.gameWorld();
        if (m.arena != null) return m.arena.toLocation(w);
        if (m.spectator != null) return m.spectator.toLocation(w);
        if (plotOf.isEmpty()) return null;
        int x1 = Integer.MAX_VALUE, z1 = Integer.MAX_VALUE, x2 = Integer.MIN_VALUE, z2 = Integer.MIN_VALUE, y = Integer.MIN_VALUE;
        for (MapConfig.Plot pl : plotOf.values()) {
            var r = pl.region();
            x1 = Math.min(x1, r.minX()); z1 = Math.min(z1, r.minZ()); x2 = Math.max(x2, r.maxX()); z2 = Math.max(z2, r.maxZ());
            y = Math.max(y, pl.spawn().by());
        }
        return new Location(w, (x1 + x2 + 1) / 2.0, y + 10, (z1 + z2 + 1) / 2.0, 0, 60);
    }

    private void startJudging() {
        phase = Phase.JUDGING;
        judgeOrder.clear();
        for (UUID u : plotOf.keySet()) if (session.playerIds().contains(u)) judgeOrder.add(u);
        Collections.shuffle(judgeOrder, session.random());
        judgeIndex = -1;
        session.broadcast("<yellow>Начинаем оценку. Авторов узнаете в конце!");
        nextPlot();
    }

    private void nextPlot() {
        judgeIndex++;
        if (judgeIndex >= judgeOrder.size()) {
            results();
            return;
        }
        phaseLeft = judgeTime;
        UUID owner = judgeOrder.get(judgeIndex);
        MapConfig.Plot plot = plotOf.get(owner);
        Location loc = plot.spawn().toLocation(session.gameWorld());
        String num = "Постройка " + (judgeIndex + 1) + " из " + judgeOrder.size();
        for (Player p : session.alivePlayers()) {
            PlayerUtil.reset(p, GameMode.ADVENTURE);
            p.setAllowFlight(true);
            p.teleport(loc);
            p.setFlying(true);
            if (owner.equals(p.getUniqueId())) {
                Msg.title(p, "<light_purple>Это твоя постройка", "<gray>Другие сейчас её оценивают");
            } else {
                giveVoteItems(p, -1);
                Msg.title(p, "<yellow>Оцени постройку!", "<gray>" + num + " · тема: " + Msg.esc(theme));
            }
        }
    }

    /** Блоки оценок от красного к зелёному; выбранная оценка (chosen) светится. */
    private void giveVoteItems(Player p, int chosen) {
        for (int i = 0; i < grades.size() && i < 9; i++) {
            Grade g = grades.get(i);
            var it = Keys.tag(Items.icon(g.material(), g.name(), List.of("<gray>ПКМ — поставить эту оценку"), i == chosen), "logic:vote:" + i);
            p.getInventory().setItem(i, it);
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
        if (idx < 0 || idx >= grades.size()) return;
        Grade g = grades.get(idx);
        scores.computeIfAbsent(owner, k -> new HashMap<>()).put(p.getUniqueId(), g.points());
        giveVoteItems(p, idx);
        p.sendActionBar(Msg.parse("<gray>Твоя оценка: </gray>" + g.name() + " <dark_gray>(можно поменять, пока идёт время)"));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 0.6f + idx * 0.2f);
    }

    /** Итоги: только теперь видно, кто что строил — места и очки в чате и над каждой постройкой. */
    private void results() {
        phase = Phase.RESULTS;
        List<UUID> order = new ArrayList<>(judgeOrder);
        order.sort(Comparator.comparingInt(this::total).reversed());
        if (order.isEmpty()) {
            session.end(List.of(), null);
            return;
        }
        session.broadcast("<gold><bold>Итоги — авторы построек:");
        for (int i = 0; i < order.size(); i++) {
            UUID u = order.get(i);
            String name = nameOf(u);
            String place = i == 0 ? "<gold>#1" : i == 1 ? "<gray>#2" : i == 2 ? "<#cd7f32>#3" : "<dark_gray>#" + (i + 1);
            session.broadcast(place + " <white>" + name + " <gray>— <yellow>" + total(u) + " очк. <dark_gray>(" + votes(u) + " оценок)");
            label(plotOf.get(u), place + "\n<white><bold>" + name + "\n<yellow>" + total(u) + " очк.");
        }
        UUID winner = order.get(0);
        Location arena = arena();
        for (Player p : session.alivePlayers()) {
            p.getInventory().clear();
            if (arena != null) p.teleport(arena);
            p.setFlying(true);
            Msg.title(p, "<gold><bold>" + nameOf(winner), "<yellow>лучшая постройка! " + total(winner) + " очк.");
        }
        session.end(List.of(winner), null);
    }

    private static String nameOf(UUID u) {
        String n = org.bukkit.Bukkit.getOfflinePlayer(u).getName();
        return n != null ? Msg.esc(n) : "?";
    }

    private int votes(UUID owner) { return scores.getOrDefault(owner, Map.of()).size(); }

    /** Табличка над постройкой (TextDisplay, исчезает вместе с игровым миром). */
    private void label(MapConfig.Plot plot, String text) {
        var w = session.gameWorld();
        var r = plot.region();
        int cx = (r.minX() + r.maxX()) / 2, cz = (r.minZ() + r.maxZ()) / 2;
        int top = plot.spawn().by();
        for (int x = r.minX(); x <= r.maxX(); x += Math.max(1, (r.maxX() - r.minX()) / 8))
            for (int z = r.minZ(); z <= r.maxZ(); z += Math.max(1, (r.maxZ() - r.minZ()) / 8))
                for (int y = r.maxY(); y > top; y--)
                    if (!w.getBlockAt(x, y, z).getType().isAir()) { top = y; break; }
        Location at = new Location(w, cx + 0.5, Math.min(r.maxY(), top) + 3, cz + 0.5);
        w.spawn(at, org.bukkit.entity.TextDisplay.class, d -> {
            d.setPersistent(false);
            d.text(Msg.parse(text));
            d.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            d.setShadowed(true);
            d.setSeeThrough(true);
            d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.Quaternionf(),
                    new org.joml.Vector3f(2.5f, 2.5f, 2.5f), new org.joml.Quaternionf()));
        });
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
        if (plot == null || !plot.region().contains(l)) return false;
        for (ru.mgp.util.Region w : session.map().walls) if (w.contains(l)) return false; // стену ломать нельзя
        return true;
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
            case REVEAL -> "Смотрим постройки";
            case JUDGING -> "Оценка";
            case RESULTS -> "Итоги";
        });
        ph.put("phase_time", Msg.time(phaseLeft));
        ph.put("plot", phase == Phase.JUDGING ? (judgeIndex + 1) + "/" + judgeOrder.size() : "-");
    }

    @Override
    public String sidebarGroup() {
        if (phase == Phase.BUILD) return "build";
        if (phase == Phase.JUDGING || phase == Phase.REVEAL) return "judging";
        return null;
    }
}
