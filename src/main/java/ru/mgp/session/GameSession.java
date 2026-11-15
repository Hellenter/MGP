package ru.mgp.session;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.PlayerInventory;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import ru.mgp.MGPPlugin;
import ru.mgp.config.*;
import ru.mgp.logic.GameLogic;
import ru.mgp.logic.GameLogics;
import ru.mgp.ui.Hotbar;
import ru.mgp.util.*;
import ru.mgp.vote.Vote;
import ru.mgp.vote.VoteType;
import ru.mgp.world.WorldService;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Одна игровая сессия: лобби ожидания → отсчёт → игра → итоги → закрытие.
 * У каждой сессии свой мир ожидания и свой игровой мир (копии шаблонов).
 */
public final class GameSession {
    public enum State { CREATING, WAITING, STARTING, PLAYING, ENDING, CLOSED }

    private static final int MAP_LOAD_LEAD = 10; // за сколько секунд до старта подводим итоги голосования и грузим карту

    private final MGPPlugin plugin;
    public final int id;
    public final ModeConfig mode;
    private final GameLogic logic;
    private final Random random = new Random();

    private final List<MapConfig.ChestSpot> autoChests = new ArrayList<>();
    /** Уже заполненные контейнеры "x y z" -> тир. Остальные заполняются при первом открытии. */
    private final Map<String, String> filledChests = new HashMap<>();
    private State state = State.CREATING;
    private final Set<UUID> players = new LinkedHashSet<>();
    private final Set<UUID> alive = new LinkedHashSet<>();
    private final Map<UUID, Integer> kills = new HashMap<>();
    private final Map<UUID, String> kitChoice = new HashMap<>();
    private final Map<String, Vote> votes = new LinkedHashMap<>();
    private final Map<String, String> chosenModifiers = new LinkedHashMap<>();
    private final Set<String> firedTriggers = new HashSet<>();
    private final Map<String, Set<UUID>> firedPerPlayer = new HashMap<>();

    private World waitingWorld;
    private World gameWorld;
    private MapConfig map;
    private CompletableFuture<World> mapLoading;
    private boolean mapLoadStarted;
    private int countdown;
    private int elapsed;
    private int endTimer;
    private Long lockedTime;

    public GameSession(MGPPlugin plugin, int id, ModeConfig mode) {
        this.plugin = plugin;
        this.id = id;
        this.mode = mode;
        this.logic = GameLogics.create(mode.type, this);
        setupVotes();
    }

    // ------------------------------------------------------------------ создание

    private void setupVotes() {
        double base = plugin.getConfig().getDouble("voting.weighted.base", 1.0);
        double per = plugin.getConfig().getDouble("voting.weighted.per-vote", 1.0);

        List<MapConfig> maps = new ArrayList<>(plugin.registry().mapsFor(mode.id));
        maps.removeIf(m -> !plugin.sessions().playable(m));
        Collections.shuffle(maps, random);
        if (mode.mapVoteOptions > 0 && maps.size() > mode.mapVoteOptions) maps = maps.subList(0, mode.mapVoteOptions);
        List<Vote.Option> mapOptions = new ArrayList<>();
        for (MapConfig m : maps) mapOptions.add(new Vote.Option(m.id, m.displayName, m.icon, m.weight, m.description));
        votes.put("map", new Vote("map", "<green>Карта", Material.FILLED_MAP, mode.mapVoteType, mapOptions, base, per));

        for (ModifierGroup g : mode.modifiers) {
            if (g.selection() == ModifierGroup.Selection.OFF) continue;
            List<Vote.Option> opts = new ArrayList<>();
            for (ModifierGroup.Option o : g.options()) opts.add(new Vote.Option(o.id(), o.display(), o.icon(), o.weight(), List.of()));
            VoteType type = g.selection() == ModifierGroup.Selection.VOTE ? g.voteType() : VoteType.NONE;
            votes.put("mod:" + g.id(), new Vote("mod:" + g.id(), g.display(), g.icon(), type, opts, base, per));
        }
        logic.registerVotes();
    }

    public void addVote(Vote v) { votes.put(v.key, v); }

    /** Запускает подготовку мира ожидания. */
    public void create() {
        String wName = WorldService.PREFIX + mode.id + "_w" + id;
        java.util.concurrent.CompletableFuture<World> wf;
        if (plugin.worlds().templateExists(mode.waitingTemplate)) {
            wf = plugin.worlds().create(mode.waitingTemplate, wName);
        } else {
            plugin.getLogger().warning("Нет шаблона лобби ожидания templates/" + mode.waitingTemplate + " для режима " + mode.id
                    + " — использую временную площадку. Своё лобби: /mgp setup export " + mode.waitingTemplate + " (стоя в мире лобби)");
            wf = plugin.worlds().createPlatform(wName, mode.waitingSpawn);
        }
        wf.whenComplete((w, ex) -> {
            if (state == State.CLOSED) {
                if (w != null) plugin.worlds().destroy(w);
                return;
            }
            if (ex != null) {
                plugin.getLogger().log(Level.SEVERE, "Не удалось создать лобби ожидания для " + mode.id, ex);
                broadcast("<red>Не удалось подготовить арену: " + Msg.esc(String.valueOf(ex.getMessage())));
                close();
                return;
            }
            waitingWorld = w;
            plugin.worlds().applyGamerules(w, mode.gamerules);
            state = State.WAITING;
            for (Player p : online()) sendToWaiting(p);
            checkStart();
        });
    }

    // ------------------------------------------------------------------ игроки

    public boolean canJoin() {
        return (state == State.CREATING || state == State.WAITING || state == State.STARTING)
                && players.size() < mode.maxPlayers
                && !(state == State.STARTING && countdown <= 2);
    }

    public boolean addPlayer(Player p) {
        if (!canJoin()) return false;
        players.add(p.getUniqueId());
        if (mode.defaultKit != null) kitChoice.put(p.getUniqueId(), mode.defaultKit);
        plugin.sessions().bind(p, this);
        if (waitingWorld != null) sendToWaiting(p);
        else Msg.send(p, "<gray>Подготавливаем арену...");
        broadcast("<gray>" + Msg.esc(p.getName()) + " <yellow>присоединился <gray>(" + players.size() + "/" + mode.maxPlayers + ")");
        checkStart();
        return true;
    }

    private void sendToWaiting(Player p) {
        PlayerUtil.reset(p, GameMode.ADVENTURE);
        p.teleport(mode.waitingSpawn.toLocation(waitingWorld));
        Hotbar.giveWaiting(p, this);
        plugin.visibility().refresh(p);
    }

    /** Убирает игрока из сессии. toHub=false — при выходе с сервера. */
    public void removePlayer(Player p, boolean toHub) {
        UUID u = p.getUniqueId();
        if (!players.remove(u)) return;
        plugin.sessions().unbind(p);
        for (Vote v : votes.values()) v.remove(u);
        boolean wasAlive = alive.remove(u);
        if (toHub) plugin.hub().send(p);
        else plugin.visibility().refresh(p);

        switch (state) {
            case CREATING, WAITING, STARTING -> {
                broadcast("<gray>" + Msg.esc(p.getName()) + " <yellow>вышел <gray>(" + players.size() + "/" + mode.maxPlayers + ")");
                if (players.isEmpty()) close();
                else checkStart();
            }
            case PLAYING -> {
                if (players.isEmpty()) close();
                else logic.onLeave(p, wasAlive);
            }
            case ENDING -> { if (players.isEmpty()) close(); }
            default -> {}
        }
    }

    public void selectKit(Player p, String kitId) {
        Kit k = mode.kits.get(kitId);
        if (k == null) return;
        if (k.permission() != null && !p.hasPermission(k.permission())) {
            Msg.send(p, "<red>Этот набор тебе недоступен.");
            return;
        }
        kitChoice.put(p.getUniqueId(), kitId);
        Msg.send(p, "<green>Выбран набор: </green>" + k.display());
    }

    public String kitOf(UUID u) { return kitChoice.get(u); }

    // ------------------------------------------------------------------ отсчёт

    private void checkStart() {
        if (state == State.WAITING && players.size() >= mode.minPlayers) {
            state = State.STARTING;
            countdown = mode.countdown;
            broadcast("<green>Набралось достаточно игроков! Старт через <yellow>" + countdown + "</yellow> сек.");
        } else if (state == State.STARTING && players.size() < mode.minPlayers) {
            state = State.WAITING;
            cancelMapLoad();
            broadcast("<red>Недостаточно игроков, отсчёт остановлен.");
        }
        if (state == State.STARTING && players.size() >= mode.maxPlayers && countdown > mode.fastCountdown) {
            countdown = mode.fastCountdown;
            broadcast("<gold>Лобби заполнено! Старт через <yellow>" + countdown + "</yellow> сек.");
        }
    }

    public boolean forceStart() {
        if (state != State.WAITING && state != State.STARTING) return false;
        if (players.isEmpty()) return false;
        int current = state == State.STARTING ? countdown : Integer.MAX_VALUE;
        state = State.STARTING;
        countdown = Math.min(current, 5);
        broadcast("<gold>Игра запущена принудительно!");
        return true;
    }

    public void tick() {
        switch (state) {
            case STARTING -> tickCountdown();
            case PLAYING -> tickGame();
            case ENDING -> { if (--endTimer <= 0) close(); }
            default -> {}
        }
    }

    private void tickCountdown() {
        if (!mapLoadStarted && countdown <= MAP_LOAD_LEAD) startMapLoad();
        if (state != State.STARTING) return;
        if (countdown > 0) {
            if (countdown <= 5 || countdown == 10 || countdown == 15 || countdown == 30 || countdown == 60) {
                broadcast("<yellow>Игра начнётся через <red>" + countdown + "</red> сек.");
                for (Player p : online()) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
            }
            countdown--;
            return;
        }
        if (gameWorld != null) startGame();
        else for (Player p : online()) p.sendActionBar(Msg.parse("<yellow>Загрузка карты..."));
    }

    private void startMapLoad() {
        mapLoadStarted = true;
        String mapId = votes.get("map").resolve(random);
        map = plugin.registry().map(mapId);
        if (map == null) {
            broadcast("<red>Нет доступных карт для этого режима.");
            close();
            return;
        }
        broadcast("<green>Карта: </green>" + map.displayName);
        for (ModifierGroup g : mode.modifiers) {
            Vote v = votes.get("mod:" + g.id());
            if (v == null) continue;
            String chosen = g.selection() == ModifierGroup.Selection.FIXED && g.fixed() != null ? g.fixed() : v.resolve(random);
            if (chosen == null) continue;
            chosenModifiers.put(g.id(), chosen);
            ModifierGroup.Option o = g.option(chosen);
            if (o != null) broadcast("<gray>" + g.display() + "<gray>: </gray>" + o.display());
        }
        logic.onVotesClosed();

        final CompletableFuture<World> f = plugin.worlds().create(map.template, WorldService.PREFIX + mode.id + "_g" + id);
        mapLoading = f;
        f.whenComplete((w, ex) -> {
            if (state == State.CLOSED || mapLoading != f) {
                if (w != null) plugin.worlds().destroy(w);
                return;
            }
            if (ex != null) {
                plugin.getLogger().log(Level.SEVERE, "Не удалось загрузить карту " + map.id, ex);
                broadcast("<red>Ошибка загрузки карты.");
                close();
                return;
            }
            gameWorld = w;
            prepareGameWorld();
        });
    }

    private void cancelMapLoad() {
        if (!mapLoadStarted) return;
        mapLoadStarted = false;
        mapLoading = null;
        if (gameWorld != null) plugin.worlds().destroy(gameWorld);
        gameWorld = null;
        map = null;
        chosenModifiers.clear();
        for (Vote v : votes.values()) v.reopen();
    }

    // ------------------------------------------------------------------ игра

    private void prepareGameWorld() {
        plugin.worlds().applyGamerules(gameWorld, mode.gamerules);
        if (map.center != null) {
            WorldBorder b = gameWorld.getWorldBorder();
            b.setCenter(map.center.x(), map.center.z());
            if (map.borderSize > 0) b.setSize(map.borderSize);
        }
        autoChests.clear();
        if (map.chests.isEmpty() && !mode.loot.isEmpty() && mode.settings.getBoolean("auto-chests", true)) detectChests();
        else fillChests(null);
        spawnTraders();
    }

    /**
     * У карты не записаны сундуки: находим контейнеры в области карты сами (CHEST -> tier1, TRAPPED_CHEST -> tier2,
     * см. import.chest-tiers в config.yml), заполняем и сохраняем список в maps/<карта>.yml, чтобы его можно было править.
     */
    private void detectChests() {
        Region box = mapBox();
        if (box == null) {
            plugin.getLogger().warning("Карта " + map.id + ": сундуки не заданы и нет области source/center, чтобы найти их автоматически");
            return;
        }
        Map<String, String> tiers = chestTiers();
        World w = gameWorld;
        MapConfig m = map;
        java.nio.file.Path template = plugin.worlds().templatesDir().resolve(m.template);
        // Читаем сундуки прямо из файлов шаблона: getTileEntities() на живом сервере их не возвращал.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<MapConfig.ChestSpot> found = new ArrayList<>();
            Map<String, Integer> seen = new TreeMap<>();
            Exception err = null;
            try {
                java.nio.file.Path dim = ru.mgp.world.RegionCutter.findOverworldDir(template);
                for (ru.mgp.world.ChestScanner.Found f : ru.mgp.world.ChestScanner.scan(dim, box.minX(), box.minZ(), box.maxX(), box.maxZ())) {
                    seen.merge(f.type(), 1, Integer::sum);
                    String tier = tiers.get(f.type());
                    if (tier != null && mode.loot.containsKey(tier)) found.add(new MapConfig.ChestSpot(new Pos(f.x(), f.y(), f.z(), 0, 0), tier));
                }
            } catch (Exception e) {
                err = e;
            }
            Exception ex = err;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (gameWorld != w || state == State.CLOSED) return;
                if (ex != null) plugin.getLogger().log(Level.WARNING, "Поиск сундуков на карте " + m.id, ex);
                autoChests.addAll(found);
                plugin.getLogger().info("Карта " + m.id + ": найдено сундуков автоматически: " + found.size()
                        + " (блоки с данными: " + seen + ")");
                fillChests(null);
                if (!found.isEmpty()) saveChests(m, found);
            });
        });
    }

    /**
     * Игрок открывает контейнер на карте. Если он ещё не заполнялся (не найден заранее), заполняем сейчас:
     * тир — из списка chests карты, а если список пуст — по типу блока (import.chest-tiers).
     */
    public void onContainerOpen(Inventory inv) {
        if (gameWorld == null || map == null || state != State.PLAYING) return;
        if (inv instanceof org.bukkit.inventory.DoubleChestInventory dc) {
            fillOnOpen(dc.getLeftSide());
            fillOnOpen(dc.getRightSide());
        } else {
            fillOnOpen(inv);
        }
    }

    private void fillOnOpen(Inventory inv) {
        Location l = inv.getLocation();
        if (l == null || !gameWorld.equals(l.getWorld())) return;
        String key = l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
        if (filledChests.containsKey(key)) return;
        String tier = null;
        for (MapConfig.ChestSpot c : map.chests) {
            if ((c.pos().bx() + " " + c.pos().by() + " " + c.pos().bz()).equals(key)) { tier = c.tier(); break; }
        }
        if (tier == null && map.chests.isEmpty() && mode.settings.getBoolean("auto-chests", true)) {
            tier = chestTiers().get(l.getBlock().getType().name());
        }
        if (tier == null) return;
        LootTable table = mode.loot.get(tier);
        if (table == null) return;
        filledChests.put(key, tier);
        table.fill(inv, random);
    }

    private Map<String, String> chestTiers() { return Cfg.chestTiers(plugin.getConfig()); }

    private Region mapBox() {
        Pos a = Pos.parse(map.yaml.getString("source.min")), b = Pos.parse(map.yaml.getString("source.max"));
        if (a != null && b != null) return Region.of(a, b);
        if (map.center != null && map.borderSize > 0) {
            double r = map.borderSize / 2;
            return Region.of(new Pos(map.center.x() - r, gameWorld.getMinHeight(), map.center.z() - r, 0, 0),
                    new Pos(map.center.x() + r, gameWorld.getMaxHeight() - 1, map.center.z() + r, 0, 0));
        }
        return null;
    }

    private void saveChests(MapConfig m, List<MapConfig.ChestSpot> list) {
        try {
            var y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(m.file);
            if (!y.getStringList("chests").isEmpty()) return;
            List<String> lines = new ArrayList<>();
            for (MapConfig.ChestSpot c : list) lines.add(c.pos().bx() + " " + c.pos().by() + " " + c.pos().bz() + " " + c.tier());
            Collections.sort(lines);
            y.set("chests", lines);
            y.save(m.file);
            plugin.registry().loadMap(m.file);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Не удалось сохранить сундуки в " + m.file, e);
        }
    }

    private void startGame() {
        state = State.PLAYING;
        elapsed = 0;
        alive.clear();
        alive.addAll(players);
        logic.onStart();
        for (Map.Entry<String, String> e : chosenModifiers.entrySet()) {
            ModifierGroup g = modifierGroup(e.getKey());
            ModifierGroup.Option o = g == null ? null : g.option(e.getValue());
            if (o != null) ActionRunner.run(this, o.actions(), null);
        }
        World w = waitingWorld;
        waitingWorld = null;
        if (w != null) plugin.worlds().destroy(w);
        plugin.visibility().refreshAll(this);
    }

    private ModifierGroup modifierGroup(String id) {
        for (ModifierGroup g : mode.modifiers) if (g.id().equals(id)) return g;
        return null;
    }

    private void tickGame() {
        elapsed++;
        for (TimelineEvent ev : mode.timeline) {
            if (ev.at() == elapsed) ActionRunner.run(this, ev.actions(), null);
        }
        if (lockedTime != null) gameWorld.setTime(lockedTime);
        checkTriggers();
        logic.onTick();
        if (state == State.PLAYING && mode.timeLimit > 0 && elapsed >= mode.timeLimit) logic.onTimeUp();
    }

    private void checkTriggers() {
        if (map == null || map.triggers.isEmpty()) return;
        List<Player> list = alivePlayers();
        for (MapConfig.Trigger t : map.triggers) {
            if (t.mode() == MapConfig.Trigger.Mode.ONCE && firedTriggers.contains(t.name())) continue;
            for (Player p : list) {
                if (!t.region().contains(p.getLocation())) continue;
                switch (t.mode()) {
                    case ONCE -> {
                        if (firedTriggers.add(t.name())) ActionRunner.run(this, t.actions(), p);
                    }
                    case PER_PLAYER -> {
                        if (firedPerPlayer.computeIfAbsent(t.name(), k -> new HashSet<>()).add(p.getUniqueId()))
                            ActionRunner.run(this, t.actions(), p);
                    }
                    case REPEAT -> ActionRunner.run(this, t.actions(), p);
                }
                if (t.mode() == MapConfig.Trigger.Mode.ONCE) break;
            }
        }
    }

    /** Раскидывает живых игроков по спавнам в случайном порядке (лишние спавны остаются пустыми). */
    public void spreadToSpawns(List<Pos> spawns) {
        if (spawns.isEmpty()) {
            plugin.getLogger().warning("На карте " + map.id + " нет спавнов!");
            return;
        }
        List<Pos> sp = new ArrayList<>(spawns);
        Collections.shuffle(sp, random);
        List<Player> ps = alivePlayers();
        Collections.shuffle(ps, random);
        if (ps.size() > sp.size()) plugin.getLogger().warning("Игроков больше, чем спавнов на карте " + map.id);
        for (int i = 0; i < ps.size(); i++) {
            Location l = sp.get(i % sp.size()).toLocation(gameWorld);
            ps.get(i).teleport(l);
        }
    }

    /** Выдаёт стартовые предметы, выбранный кит и его эффекты. */
    public void equip(Player p) {
        PlayerInventory inv = p.getInventory();
        for (ItemSpec s : mode.startItems) give(inv, s);
        String kitId = kitChoice.get(p.getUniqueId());
        Kit kit = kitId == null ? null : mode.kits.get(kitId);
        if (kit != null) {
            for (ItemSpec s : kit.items()) give(inv, s);
            ActionRunner.run(this, kit.actions(), p);
        }
    }

    private static void give(PlayerInventory inv, ItemSpec s) {
        ItemStack it = s.build();
        if (s.slot == null) {
            inv.addItem(it);
            return;
        }
        switch (s.slot.toLowerCase(Locale.ROOT)) {
            case "head", "helmet" -> inv.setItem(EquipmentSlot.HEAD, it);
            case "chest", "chestplate" -> inv.setItem(EquipmentSlot.CHEST, it);
            case "legs", "leggings" -> inv.setItem(EquipmentSlot.LEGS, it);
            case "feet", "boots" -> inv.setItem(EquipmentSlot.FEET, it);
            case "offhand" -> inv.setItem(EquipmentSlot.OFF_HAND, it);
            default -> {
                try { inv.setItem(Integer.parseInt(s.slot), it); } catch (NumberFormatException e) { inv.addItem(it); }
            }
        }
    }

    public void fillChests(String onlyTier) {
        if (map == null || gameWorld == null) return;
        List<MapConfig.ChestSpot> spots = new ArrayList<>(map.chests);
        spots.addAll(autoChests);
        // контейнеры, заполненные при открытии: после refill снова заполнятся при следующем открытии
        filledChests.values().removeIf(t -> onlyTier == null || onlyTier.equalsIgnoreCase(t));
        for (MapConfig.ChestSpot c : spots) {
            if (onlyTier != null && !onlyTier.equalsIgnoreCase(c.tier())) continue;
            filledChests.put(c.pos().bx() + " " + c.pos().by() + " " + c.pos().bz(), c.tier());
            LootTable table = mode.loot.get(c.tier());
            if (table == null) {
                plugin.getLogger().warning("Нет таблицы лута '" + c.tier() + "' в режиме " + mode.id);
                continue;
            }
            Block b = gameWorld.getBlockAt(c.pos().bx(), c.pos().by(), c.pos().bz());
            BlockState st = b.getState();
            Inventory inv = st instanceof Chest chest ? chest.getBlockInventory()
                    : st instanceof Container cont ? cont.getInventory() : null;
            if (inv != null) table.fill(inv, random);
        }
    }

    private void spawnTraders() {
        var professions = RegistryAccess.registryAccess().getRegistry(RegistryKey.VILLAGER_PROFESSION);
        for (MapConfig.TraderSpot t : map.traders) {
            List<TradeSpec> specs = mode.trades.get(t.set());
            if (specs == null) {
                plugin.getLogger().warning("Нет набора сделок '" + t.set() + "' в режиме " + mode.id);
                continue;
            }
            Villager v = gameWorld.spawn(t.pos().toLocation(gameWorld), Villager.class);
            v.setAI(false);
            v.setInvulnerable(true);
            v.setSilent(true);
            v.setPersistent(true);
            Villager.Profession prof = professions.get(NamespacedKey.minecraft(t.profession().toLowerCase(Locale.ROOT)));
            if (prof != null) v.setProfession(prof);
            v.setVillagerLevel(5);
            v.customName(Msg.parse(t.name()));
            v.setCustomNameVisible(true);
            List<MerchantRecipe> recipes = new ArrayList<>();
            for (TradeSpec s : specs) {
                MerchantRecipe r = new MerchantRecipe(s.result().build(), s.maxUses());
                r.addIngredient(s.cost().build());
                if (s.cost2() != null) r.addIngredient(s.cost2().build());
                r.setExperienceReward(false);
                recipes.add(r);
            }
            v.setRecipes(recipes);
        }
    }

    /** Игрок выбыл: становится наблюдателем (летает, невидим для живых). */
    public void eliminate(Player p) {
        alive.remove(p.getUniqueId());
        makeSpectator(p);
    }

    public void makeSpectator(Player p) {
        PlayerUtil.reset(p, GameMode.ADVENTURE);
        p.setAllowFlight(true);
        p.setFlying(true);
        if (map != null && map.spectator != null) p.teleport(map.spectator.toLocation(gameWorld));
        Hotbar.giveSpectator(p);
        plugin.visibility().refreshAll(this);
    }

    public Location spectatorLocation() {
        if (map != null && map.spectator != null) return map.spectator.toLocation(gameWorld);
        if (map != null && !map.spawns.isEmpty()) return map.spawns.get(0).toLocation(gameWorld);
        return gameWorld != null ? gameWorld.getSpawnLocation() : plugin.hub().location();
    }

    /** Завершает игру. winners может быть пустым (ничья). */
    public void end(Collection<UUID> winners, String reason) {
        if (state != State.PLAYING) return;
        state = State.ENDING;
        endTimer = mode.endDelay;
        List<String> names = new ArrayList<>();
        for (UUID u : winners) {
            Player w = Bukkit.getPlayer(u);
            if (w != null) names.add(Msg.esc(w.getName()));
        }
        if (reason != null) broadcast(reason);
        broadcast(names.isEmpty() ? "<yellow>Игра окончена! Победителей нет."
                : "<gold><bold>Победа:</bold> <yellow>" + String.join(", ", names));
        for (Player p : online()) {
            boolean win = winners.contains(p.getUniqueId());
            Msg.title(p, win ? "<gold><bold>ПОБЕДА!" : "<red><bold>ИГРА ОКОНЧЕНА",
                    names.isEmpty() ? "" : "<yellow>" + String.join(", ", names));
            if (win) ActionRunner.run(this, mode.winActions, p);
        }
        logic.onEnd();
    }

    /** Закрывает сессию: игроки в хаб, миры выгружаются и удаляются. */
    public void close() {
        if (state == State.CLOSED) return;
        state = State.CLOSED;
        for (UUID u : new ArrayList<>(players)) {
            Player p = Bukkit.getPlayer(u);
            players.remove(u);
            if (p != null) {
                plugin.sessions().unbind(p);
                plugin.hub().send(p);
            }
        }
        alive.clear();
        if (waitingWorld != null) plugin.worlds().destroy(waitingWorld);
        if (gameWorld != null) plugin.worlds().destroy(gameWorld);
        waitingWorld = null;
        gameWorld = null;
        plugin.sessions().remove(this);
    }

    // ------------------------------------------------------------------ мир / действия

    public void setTime(long time, boolean lock) {
        if (gameWorld == null) return;
        gameWorld.setTime(time);
        lockedTime = lock ? time : null;
    }

    public void setWeather(String type) {
        if (gameWorld == null) return;
        switch (type.toLowerCase(Locale.ROOT)) {
            case "rain" -> { gameWorld.setStorm(true); gameWorld.setThundering(false); gameWorld.setWeatherDuration(Integer.MAX_VALUE / 2); }
            case "thunder", "storm" -> { gameWorld.setStorm(true); gameWorld.setThundering(true); gameWorld.setWeatherDuration(Integer.MAX_VALUE / 2); gameWorld.setThunderDuration(Integer.MAX_VALUE / 2); }
            default -> { gameWorld.setStorm(false); gameWorld.setThundering(false); gameWorld.setClearWeatherDuration(Integer.MAX_VALUE / 2); }
        }
    }

    // ------------------------------------------------------------------ плейсхолдеры для инфо-панели

    public Map<String, String> placeholders(Player p) {
        Map<String, String> ph = new HashMap<>();
        ph.put("mode", mode.displayName);
        ph.put("map", map != null ? map.displayName : "<gray>голосование");
        ph.put("players", String.valueOf(players.size()));
        ph.put("max", String.valueOf(mode.maxPlayers));
        ph.put("min", String.valueOf(mode.minPlayers));
        ph.put("need", String.valueOf(Math.max(0, mode.minPlayers - players.size())));
        ph.put("countdown", state == State.STARTING ? String.valueOf(countdown) : "-");
        ph.put("alive", String.valueOf(alive.size()));
        ph.put("kills", String.valueOf(kills.getOrDefault(p.getUniqueId(), 0)));
        ph.put("elapsed", Msg.time(elapsed));
        ph.put("time", mode.timeLimit > 0 ? Msg.time(mode.timeLimit - elapsed) : Msg.time(elapsed));
        ph.put("session", String.valueOf(id));
        String kit = kitChoice.get(p.getUniqueId());
        ph.put("kit", kit != null && mode.kits.containsKey(kit) ? mode.kits.get(kit).display() : "-");
        TimelineEvent next = null;
        for (TimelineEvent ev : mode.timeline) if (ev.at() > elapsed && !ev.name().isEmpty()) { next = ev; break; }
        ph.put("next_event", next != null ? next.name() : "-");
        ph.put("next_event_time", next != null ? Msg.time(next.at() - elapsed) : "");
        ph.put("state", switch (state) {
            case CREATING -> "Подготовка";
            case WAITING -> "Ожидание";
            case STARTING -> "Старт через " + countdown;
            case PLAYING -> "Игра";
            case ENDING -> "Конец";
            case CLOSED -> "-";
        });
        logic.placeholders(p, ph);
        return ph;
    }

    public List<String> sidebarLines() {
        String group = switch (state) {
            case CREATING, WAITING, STARTING -> "waiting";
            case PLAYING -> {
                String g = logic.sidebarGroup();
                yield g != null && mode.sidebar.containsKey(g) ? g : "playing";
            }
            default -> "ending";
        };
        return mode.sidebar.getOrDefault(group, List.of());
    }

    // ------------------------------------------------------------------ геттеры

    public State state() { return state; }
    public GameLogic logic() { return logic; }
    public MapConfig map() { return map; }
    public World gameWorld() { return gameWorld; }
    public World waitingWorld() { return waitingWorld; }
    public Random random() { return random; }
    public int elapsed() { return elapsed; }
    public Map<String, Vote> votes() { return votes; }
    public MGPPlugin plugin() { return plugin; }

    public boolean owns(World w) { return w != null && (w.equals(waitingWorld) || w.equals(gameWorld)); }
    public boolean isPlaying() { return state == State.PLAYING; }
    public boolean isAlive(UUID u) { return alive.contains(u); }
    public boolean isAlive(Player p) { return alive.contains(p.getUniqueId()); }
    public boolean isSpectator(UUID u) { return (state == State.PLAYING || state == State.ENDING) && players.contains(u) && !alive.contains(u); }
    public Set<UUID> aliveIds() { return Collections.unmodifiableSet(alive); }
    public Set<UUID> playerIds() { return Collections.unmodifiableSet(players); }
    public int size() { return players.size(); }

    public void addKill(UUID u) { kills.merge(u, 1, Integer::sum); }
    public int kills(UUID u) { return kills.getOrDefault(u, 0); }

    public List<Player> online() {
        List<Player> out = new ArrayList<>();
        for (UUID u : players) { Player p = Bukkit.getPlayer(u); if (p != null) out.add(p); }
        return out;
    }

    public List<Player> alivePlayers() {
        List<Player> out = new ArrayList<>();
        for (UUID u : alive) { Player p = Bukkit.getPlayer(u); if (p != null) out.add(p); }
        return out;
    }

    public void broadcast(String mm) {
        Component c = Msg.parse(mm);
        for (Player p : online()) p.sendMessage(c);
    }
}
