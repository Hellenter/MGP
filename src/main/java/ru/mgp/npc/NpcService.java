package ru.mgp.npc;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ru.mgp.MGPPlugin;
import ru.mgp.session.ActionRunner;
import ru.mgp.session.GameSession;
import ru.mgp.ui.GameSelectorMenu;
import ru.mgp.util.Msg;
import ru.mgp.util.Pos;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

/**
 * NPC (plugins/MGP/npcs.yml): по клику ставят в очередь режима, открывают меню игр, переводят на другой сервер
 * или выполняют действия скриптов. Над головой — подпись с плейсхолдерами ({players:режим}, {online}).
 *
 * NPC без карты стоят в своём мире (хаб и т.п.). NPC карты (map: id) появляются в каждой игре на этой карте
 * в той же точке; в мире-источнике карты их видят только админы — чтобы настраивать.
 * Shift+ПКМ админом вне игры — меню настройки.
 */
public final class NpcService implements Listener {
    public static final List<String> TYPES = List.of("MANNEQUIN", "VILLAGER", "WANDERING_TRADER", "ZOMBIE", "SKELETON", "PIGLIN",
            "PILLAGER", "WITCH", "IRON_GOLEM", "SNOW_GOLEM", "ALLAY", "BLAZE", "CREEPER", "ENDERMAN", "FOX", "WOLF", "CAT", "ARMOR_STAND");

    /** Одна NPC. */
    public static final class Npc {
        public final String id;
        public String map;      // null — NPC мира (хаб); иначе NPC карты
        public String world;
        public Pos pos;
        public String type = "MANNEQUIN";
        public String skin;     // ник игрока для MANNEQUIN
        public String name;
        public final List<String> lines = new ArrayList<>();
        public final List<String> actions = new ArrayList<>();
        public boolean look = true;

        Npc(String id) { this.id = id; }
    }

    private record Live(Npc npc, LivingEntity body, TextDisplay holo, boolean preview) {
        boolean valid() { return body.isValid(); }
    }

    private final MGPPlugin plugin;
    private final File file;
    private final NamespacedKey key;
    private final Map<String, Npc> npcs = new LinkedHashMap<>();
    /** NPC мира и превью NPC карт: id -> живая сущность. */
    private final Map<String, Live> placed = new HashMap<>();
    /** NPC в мирах игр. */
    private final List<Live> inGames = new ArrayList<>();
    private final Map<UUID, Consumer<String>> chat = new HashMap<>();
    private final Map<UUID, Long> lastClick = new HashMap<>();
    private BukkitTask task;
    private int ticks;

    public NpcService(MGPPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "npcs.yml");
        this.key = new NamespacedKey(plugin, "npc");
    }

    public MGPPlugin plugin() { return plugin; }

    // ------------------------------------------------------------------ хранение

    public void load() {
        despawnPlaced();
        npcs.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("npcs");
        if (root != null) for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Npc n = new Npc(id);
            n.map = s.getString("map");
            n.world = s.getString("world");
            n.pos = Pos.parse(s.getString("pos"));
            n.type = s.getString("type", "MANNEQUIN").toUpperCase(Locale.ROOT);
            n.skin = s.getString("skin");
            n.name = s.getString("name");
            n.lines.addAll(s.getStringList("lines"));
            n.actions.addAll(s.getStringList("actions"));
            n.look = s.getBoolean("look", true);
            if (n.pos == null) { plugin.getLogger().warning("NPC " + id + ": нет pos"); continue; }
            npcs.put(id, n);
        }
    }

    public boolean save() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of(
                "NPC: /mgp npc create <id> [карта], настройка — Shift+ПКМ по NPC или /mgp npc edit <id>.",
                "type: MANNEQUIN (человек со скином игрока skin: <ник>) или любая живая сущность: VILLAGER, ZOMBIE...",
                "lines: подпись над головой; {players:<режим>} — сколько играют, {online} — онлайн сервера.",
                "actions: join: <режим> | menu | server: <id> | leave | player-command: <команда> | wait: <сек>",
                "  и любые действия скриптов (message:, title:, sound:, command:, teleport:...). NPC карты (map:) работают в игре."));
        for (Npc n : npcs.values()) {
            String p = "npcs." + n.id + ".";
            if (n.map != null) y.set(p + "map", n.map);
            y.set(p + "world", n.world);
            y.set(p + "pos", n.pos.serialize());
            y.set(p + "type", n.type);
            if (n.skin != null) y.set(p + "skin", n.skin);
            if (n.name != null) y.set(p + "name", n.name);
            y.set(p + "lines", n.lines);
            y.set(p + "actions", n.actions);
            y.set(p + "look", n.look);
        }
        try {
            y.save(file);
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить npcs.yml: " + e.getMessage());
            return false;
        }
    }

    public Collection<Npc> all() { return Collections.unmodifiableCollection(npcs.values()); }

    public Npc get(String id) { return id == null ? null : npcs.get(id.toLowerCase(Locale.ROOT)); }

    public Npc create(String id, String map, Location at) {
        Npc n = new Npc(id.toLowerCase(Locale.ROOT));
        n.map = map;
        n.world = at.getWorld().getName();
        n.pos = Pos.of(at);
        n.name = "<yellow><bold>" + id;
        npcs.put(n.id, n);
        save();
        respawn(n);
        return n;
    }

    public void delete(Npc n) {
        npcs.remove(n.id);
        save();
        Live l = placed.remove(n.id);
        if (l != null) remove(l);
        inGames.removeIf(g -> { if (g.npc() == n) { remove(g); return true; } return false; });
    }

    /** Изменили NPC: сохранить и пересоздать сущности (в мире и в идущих играх). */
    public void changed(Npc n) {
        save();
        respawn(n);
        List<Live> games = new ArrayList<>();
        inGames.removeIf(g -> { if (g.npc() == n) { games.add(g); return true; } return false; });
        for (Live g : games) {
            World w = g.body().getWorld();
            remove(g);
            Live l = spawn(n, w, false);
            if (l != null) inGames.add(l);
        }
    }

    private void respawn(Npc n) {
        Live l = placed.remove(n.id);
        if (l != null) remove(l);
        tryPlace(n);
    }

    // ------------------------------------------------------------------ сущности

    public void start() {
        // остатки от прошлого запуска (если сущность всё же сохранилась в мире)
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities())
            if (e.getPersistentDataContainer().has(key, PersistentDataType.STRING)) e.remove();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 2L);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        despawnPlaced();
        for (Live l : inGames) remove(l);
        inGames.clear();
    }

    private void despawnPlaced() {
        for (Live l : placed.values()) remove(l);
        placed.clear();
    }

    /** Мир игры готов: поставить NPC этой карты. */
    public void spawnInGame(World w, String mapId) {
        for (Npc n : npcs.values()) {
            if (n.map == null || !n.map.equalsIgnoreCase(mapId)) continue;
            Live l = spawn(n, w, false);
            if (l != null) inGames.add(l);
        }
    }

    private void tick() {
        ticks += 2;
        if (ticks % 20 == 0) {
            for (Npc n : npcs.values()) {
                Live l = placed.get(n.id);
                if (l != null && l.valid()) continue;
                if (l != null) { remove(l); placed.remove(n.id); }
                tryPlace(n);
            }
            inGames.removeIf(l -> { if (!l.valid()) { remove(l); return true; } return false; });
            // превью NPC карт — только админам
            for (Live l : placed.values()) {
                if (!l.preview()) continue;
                for (Player p : l.body().getWorld().getPlayers()) {
                    if (!p.hasPermission("mgp.admin")) continue;
                    if (!p.canSee(l.body())) { p.showEntity(plugin, l.body()); if (l.holo() != null) p.showEntity(plugin, l.holo()); }
                }
            }
        }
        if (ticks % 40 == 0) {
            for (Live l : placed.values()) updateText(l);
            for (Live l : inGames) updateText(l);
        }
        if (ticks % 4 == 0) {
            for (Live l : placed.values()) look(l);
            for (Live l : inGames) look(l);
        }
    }

    private void tryPlace(Npc n) {
        World w = n.world == null ? null : Bukkit.getWorld(n.world);
        if (w == null || !w.isChunkLoaded((int) Math.floor(n.pos.x()) >> 4, (int) Math.floor(n.pos.z()) >> 4)) return;
        // NPC карты не ставим в мирах игр — там их ставит сама игра
        if (plugin.sessions().byWorld(w) != null) return;
        Live l = spawn(n, w, n.map != null);
        if (l != null) placed.put(n.id, l);
    }

    private Live spawn(Npc n, World w, boolean preview) {
        EntityType type;
        try {
            type = EntityType.valueOf(n.type);
        } catch (IllegalArgumentException e) {
            type = EntityType.MANNEQUIN;
        }
        Class<? extends Entity> cls = type.getEntityClass();
        if (cls == null || !LivingEntity.class.isAssignableFrom(cls) || !type.isSpawnable()) type = EntityType.MANNEQUIN;
        Location at = n.pos.toLocation(w);
        LivingEntity body;
        try {
            body = (LivingEntity) w.spawnEntity(at, type, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM, e -> setup((LivingEntity) e, n, preview));
        } catch (Exception ex) {
            plugin.getLogger().warning("NPC " + n.id + ": не удалось создать " + type + ": " + ex.getMessage());
            return null;
        }
        TextDisplay holo = w.spawn(at.clone().add(0, body.getHeight() + 0.3, 0), TextDisplay.class, x -> {
            x.setPersistent(false);
            x.getPersistentDataContainer().set(key, PersistentDataType.STRING, n.id);
            x.setBillboard(Display.Billboard.CENTER);
            x.setShadowed(true);
            x.setDefaultBackground(false);
            x.setBackgroundColor(org.bukkit.Color.fromARGB(0x40000000));
            x.setLineWidth(240);
            x.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1f, 1f, 1f), new Quaternionf()));
            if (preview) x.setVisibleByDefault(false);
        });
        Live l = new Live(n, body, holo, preview);
        updateText(l);
        return l;
    }

    private void setup(LivingEntity e, Npc n, boolean preview) {
        e.setPersistent(false);
        e.getPersistentDataContainer().set(key, PersistentDataType.STRING, n.id);
        e.setInvulnerable(true);
        e.setSilent(true);
        e.setCollidable(false);
        e.setRemoveWhenFarAway(false);
        e.setCanPickupItems(false);
        if (e instanceof Mob m) m.setAI(false);
        if (e instanceof Mannequin mq) {
            mq.setImmovable(true);
            mq.setDescription(null);
            if (n.skin != null && n.skin.matches("[A-Za-z0-9_]{1,16}"))
                mq.setProfile(ResolvableProfile.resolvableProfile().name(n.skin).build());
        }
        if (e instanceof ArmorStand as) { as.setArms(true); as.setBasePlate(false); }
        if (e instanceof Villager v) v.setProfession(Villager.Profession.NONE);
        if (preview) e.setVisibleByDefault(false);
    }

    private void remove(Live l) {
        if (l.body().isValid()) l.body().remove();
        if (l.holo() != null && l.holo().isValid()) l.holo().remove();
    }

    private void updateText(Live l) {
        if (l.holo() == null || !l.holo().isValid()) return;
        Npc n = l.npc();
        StringBuilder sb = new StringBuilder();
        if (l.preview()) sb.append("<dark_gray>[NPC карты ").append(Msg.esc(n.map)).append(": ").append(n.id).append("]</dark_gray>\n");
        if (n.name != null && !n.name.isBlank()) sb.append(n.name);
        for (String line : n.lines) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append(placeholders(line));
        }
        var text = Msg.parse(sb.toString());
        if (!text.equals(l.holo().text())) l.holo().text(text);
        double h = l.body().getHeight() + 0.25;
        Location want = l.body().getLocation().add(0, h, 0);
        if (l.holo().getLocation().distanceSquared(want) > 0.01) l.holo().teleport(want);
    }

    private String placeholders(String s) {
        if (s.indexOf('{') < 0) return s;
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int a = s.indexOf('{', i);
            int b = a < 0 ? -1 : s.indexOf('}', a);
            if (a < 0 || b < 0) { out.append(s, i, s.length()); break; }
            out.append(s, i, a);
            String ph = s.substring(a + 1, b).trim().toLowerCase(Locale.ROOT);
            if (ph.equals("online")) out.append(Bukkit.getOnlinePlayers().size());
            else if (ph.startsWith("players:")) out.append(plugin.sessions().playersIn(ph.substring(8).trim()));
            else if (ph.equals("players")) out.append(plugin.sessions().sessions().stream().mapToInt(GameSession::size).sum());
            else out.append(s, a, b + 1);
            i = b + 1;
        }
        return out.toString();
    }

    /** Повернуться к ближайшему игроку в 8 блоках. */
    private void look(Live l) {
        if (!l.npc().look || !l.valid()) return;
        LivingEntity e = l.body();
        Player best = null;
        double bd = 64;
        for (Player p : e.getWorld().getPlayers()) {
            if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            double d = p.getLocation().distanceSquared(e.getLocation());
            if (d < bd) { bd = d; best = p; }
        }
        Location base = l.npc().pos.toLocation(e.getWorld());
        float yaw = base.getYaw(), pitch = base.getPitch();
        if (best != null) {
            Location eye = e.getEyeLocation(), to = best.getEyeLocation();
            double dx = to.getX() - eye.getX(), dy = to.getY() - eye.getY(), dz = to.getZ() - eye.getZ();
            yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        }
        if (Math.abs(e.getYaw() - yaw) < 1 && Math.abs(e.getPitch() - pitch) < 1) return;
        e.setRotation(yaw, pitch);
        e.setBodyYaw(yaw);
    }

    // ------------------------------------------------------------------ клики

    private Npc npcOf(Entity e) {
        String id = e.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return id == null ? null : npcs.get(id);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEntityEvent e) {
        Npc n = npcOf(e.getRightClicked());
        if (n == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        click(e.getPlayer(), n, e.getRightClicked(), true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent e) {
        Npc n = npcOf(e.getEntity());
        if (n == null) return;
        e.setCancelled(true);
        if (e instanceof EntityDamageByEntityEvent d && d.getDamager() instanceof Player p) click(p, n, e.getEntity(), false);
    }

    private void click(Player p, Npc n, Entity e, boolean right) {
        long now = System.currentTimeMillis();
        Long last = lastClick.get(p.getUniqueId());
        if (last != null && now - last < 400) return;
        lastClick.put(p.getUniqueId(), now);
        GameSession s = plugin.sessions().byWorld(e.getWorld());
        if (s == null && right && p.isSneaking() && p.hasPermission("mgp.admin")) {
            new NpcMenu(this, n).open(p);
            return;
        }
        if (n.map != null && s == null) {
            Msg.send(p, "<gray>Это NPC карты " + Msg.esc(n.map) + " — работает в игре. <yellow>Shift+ПКМ</yellow> — настроить.");
            return;
        }
        if (n.actions.isEmpty()) {
            if (p.hasPermission("mgp.admin")) Msg.send(p, "<gray>У NPC " + n.id + " нет действий. <yellow>Shift+ПКМ</yellow> — настроить.");
            return;
        }
        if (s != null && !s.isAlive(p)) return;
        run(p, s, n.actions, 0);
    }

    /** Действия NPC по порядку; wait: — пауза. */
    private void run(Player p, GameSession s, List<String> acts, int from) {
        for (int i = from; i < acts.size(); i++) {
            String raw = acts.get(i);
            int c = raw.indexOf(':');
            String type = (c < 0 ? raw : raw.substring(0, c)).trim().toLowerCase(Locale.ROOT);
            String arg = c < 0 ? "" : raw.substring(c + 1).trim();
            try {
                switch (type) {
                    case "wait" -> {
                        double sec = Double.parseDouble(arg.replace(',', '.'));
                        int next = i + 1;
                        Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            if (!p.isOnline()) return;
                            if (s != null && (!s.isPlaying() || !s.isAlive(p))) return;
                            run(p, s, acts, next);
                        }, Math.max(1L, Math.round(sec * 20)));
                        return;
                    }
                    case "join", "play" -> {
                        if (plugin.sessions().of(p) != null) { Msg.send(p, "<red>Ты уже в игре — сначала /leave"); return; }
                        plugin.sessions().join(p, arg);
                    }
                    case "menu" -> new GameSelectorMenu(plugin).open(p);
                    case "server" -> plugin.servers().transfer(p, arg);
                    case "leave", "hub" -> plugin.sessions().leave(p);
                    case "player-command" -> p.performCommand(stripSlash(arg).replace("{player}", p.getName()));
                    default -> {
                        if (s != null) ActionRunner.run(s, List.of(raw), p);
                        else hubAction(p, type, arg, raw);
                    }
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("NPC: ошибка в действии '" + raw + "': " + ex.getMessage());
            }
        }
    }

    private static String stripSlash(String s) { return s.startsWith("/") ? s.substring(1) : s; }

    /** Действия вне игры (в хабе): то, что не требует игры. */
    private void hubAction(Player p, String type, String arg, String raw) {
        Map<String, String> ph = Map.of("player", Msg.esc(p.getName()));
        switch (type) {
            case "message" -> p.sendMessage(Msg.parse(arg, ph));
            case "title" -> {
                String[] parts = arg.split("\\|", 2);
                Msg.title(p, Msg.apply(parts[0].trim(), ph), parts.length > 1 ? Msg.apply(parts[1].trim(), ph) : "");
            }
            case "actionbar" -> p.sendActionBar(Msg.parse(arg, ph));
            case "sound" -> p.playSound(Sound.sound(Key.key(arg.split("\\s+")[0]), Sound.Source.MASTER, 1f, 1f));
            case "command" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), stripSlash(arg).replace("{player}", p.getName()));
            case "teleport" -> {
                Pos to = Pos.parse(arg);
                if (to == null) throw new IllegalArgumentException("ожидалось teleport: x y z [yaw pitch]");
                Location l = to.toLocation(p.getWorld());
                if (arg.trim().split("\\s+").length < 5) { l.setYaw(p.getYaw()); l.setPitch(p.getPitch()); }
                p.teleport(l);
            }
            default -> Msg.send(p, "<gray>Это действие работает только в игре: " + Msg.esc(raw));
        }
    }

    // ------------------------------------------------------------------ ввод в чат

    /** Ждать строку в чате. «отмена» — отменить. */
    void ask(Player p, String hint, Consumer<String> done) {
        p.closeInventory();
        chat.put(p.getUniqueId(), done);
        Msg.send(p, hint);
        Msg.send(p, "<dark_gray>Напиши в чат. «отмена» — отменить. Цвета: <red>...</red>, <bold>...</bold>");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Consumer<String> c = chat.remove(e.getPlayer().getUniqueId());
        if (c == null) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (text.equalsIgnoreCase("отмена") || text.equalsIgnoreCase("cancel") || text.equals("-")) {
                e.getPlayer().sendActionBar(Msg.parse("<red>Отменено"));
                return;
            }
            c.accept(text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        chat.remove(e.getPlayer().getUniqueId());
        lastClick.remove(e.getPlayer().getUniqueId());
    }
}
