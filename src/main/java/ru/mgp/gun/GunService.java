package ru.mgp.gun;

import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import ru.mgp.MGPPlugin;
import ru.mgp.session.GameSession;
import ru.mgp.util.Cfg;
import ru.mgp.util.Keys;
import ru.mgp.util.Msg;

import java.io.File;
import java.util.*;

/**
 * Огнестрельное оружие (guns.yml): выстрел лучом, режимы огня (F), перезарядка (Q), патроны, отдача, прицел (присед).
 * Модели и звуки — из ресурспака QualityArmory (см. {@link PackService}); без пака оружие выглядит как арбалет.
 *
 * Состояние магазина и режима держится в памяти по UID экземпляра оружия и записывается в предмет только
 * когда оружие не в руке (смена слота, выход) или после перезарядки — иначе клиент «передёргивает» предмет при каждом выстреле.
 */
public final class GunService implements Listener {
    private static GunService instance;

    private final MGPPlugin plugin;
    private final NamespacedKey kGun, kUid, kMag, kMode, kLoaded, kAmmo;
    private final Map<String, GunSpec> guns = new LinkedHashMap<>();
    private final Map<String, AmmoSpec> ammo = new LinkedHashMap<>();
    private final Map<UUID, GunState> states = new HashMap<>();
    private final Map<UUID, Shooter> shooters = new HashMap<>();
    private final PackService pack;
    private Material base = Material.STICK;
    private int triggerHold = 5;
    /** Стрельба на ЛКМ (true) или на ПКМ, как раньше (settings.fire-button: left|right). */
    private boolean fireLeft = true;
    /** ЛКМ: сколько тиков после клика идёт очередь (клиент не сообщает, что ЛКМ держат в воздухе). */
    private int clickHold = 6;
    /** С какого расстояния от стрелка начинается след пули (чтобы не закрывал прицел). */
    private double tracerStart = 10;
    private boolean onlyInGames = true, tracer = true, breakGlass = false, hud = true;
    private long now;
    /** Попадание, которое сейчас наносит урон (для отбрасывания). */
    private Shot current;

    record AmmoSpec(String id, String name, int model) {}

    private record Shot(LivingEntity target, double knockback) {}

    /** Магазин и режим одного экземпляра оружия. */
    private static final class GunState {
        int mag;
        int mode;
        final ArrayDeque<String> loaded = new ArrayDeque<>(); // типы заряженных разрывных стрел
    }

    /** Курок и перезарядка игрока. */
    private static final class Shooter {
        long lastTrigger = -100;
        /** До какого тика продолжается очередь. */
        long holdUntil = -100;
        long lastUse = -100;
        double nextShot;
        int burstLeft;
        UUID reloadGun;
        long reloadStart, reloadEnd;
        boolean zoomed;
        String lastHud;
    }

    public GunService(MGPPlugin plugin) {
        instance = this;
        this.plugin = plugin;
        kGun = new NamespacedKey(plugin, "gun");
        kUid = new NamespacedKey(plugin, "gun_uid");
        kMag = new NamespacedKey(plugin, "gun_mag");
        kMode = new NamespacedKey(plugin, "gun_mode");
        kLoaded = new NamespacedKey(plugin, "gun_loaded");
        kAmmo = new NamespacedKey(plugin, "ammo");
        pack = new PackService(plugin);
        Bukkit.getPluginManager().registerEvents(pack, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public static GunService get() { return instance; }

    public PackService pack() { return pack; }

    // ------------------------------------------------------------------ конфиг

    public void load() {
        File f = new File(plugin.getDataFolder(), "guns.yml");
        if (!f.exists()) plugin.saveResource("guns.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        guns.clear();
        ammo.clear();
        ConfigurationSection s = y.getConfigurationSection("settings");
        if (s != null) {
            base = Cfg.material(s.getString("base-item", "STICK"), Material.STICK);
            triggerHold = Math.max(4, s.getInt("trigger-hold", 5));
            fireLeft = !s.getString("fire-button", "left").trim().equalsIgnoreCase("right");
            clickHold = Math.max(1, s.getInt("click-hold", 6));
            onlyInGames = s.getBoolean("only-in-games", true);
            tracer = s.getBoolean("tracer", true);
            tracerStart = Math.max(1.2, s.getDouble("tracer-start", 10));
            breakGlass = s.getBoolean("break-glass", false);
            hud = s.getBoolean("hud", true);
        }
        ConfigurationSection a = y.getConfigurationSection("ammo");
        if (a != null) for (String id : a.getKeys(false)) {
            ConfigurationSection c = a.getConfigurationSection(id);
            if (c != null) ammo.put(id.toLowerCase(Locale.ROOT), new AmmoSpec(id.toLowerCase(Locale.ROOT), c.getString("name", "<gray>Патроны " + id), c.getInt("model", 0)));
        }
        ConfigurationSection g = y.getConfigurationSection("guns");
        if (g != null) for (String id : g.getKeys(false)) {
            ConfigurationSection c = g.getConfigurationSection(id);
            if (c == null) continue;
            try { guns.put(id.toLowerCase(Locale.ROOT), GunSpec.parse(id.toLowerCase(Locale.ROOT), c)); }
            catch (Exception e) { plugin.getLogger().warning("guns.yml: оружие " + id + " пропущено: " + e.getMessage()); }
        }
        pack.load(y.getConfigurationSection("resource-pack"));
        plugin.getLogger().info("Оружие: " + guns.size() + ", патронов: " + ammo.size());
    }

    public Map<String, GunSpec> guns() { return Collections.unmodifiableMap(guns); }
    public Set<String> ammoTypes() { return Collections.unmodifiableSet(ammo.keySet()); }

    // ------------------------------------------------------------------ предметы

    /** Новое оружие (без UID — он появится при первом выстреле). null, если такого оружия нет. */
    public ItemStack item(String id) {
        GunSpec g = id == null ? null : guns.get(id.toLowerCase(Locale.ROOT));
        if (g == null) return null;
        ItemStack it = new ItemStack(base);
        ItemMeta m = it.getItemMeta();
        m.displayName(Msg.item(g.name()));
        List<String> lore = new ArrayList<>(g.lore());
        if (g.explosive()) lore.add(g.arrowAmmo() ? "<gray>Стреляет разрывными стрелами" : "<gray>Взрывной снаряд");
        else lore.add("<gray>Урон: <white>" + fmt(g.damage()) + (g.pellets() > 1 ? " ×" + g.pellets() : "")
                + " <dark_gray>· <gray>" + fmt(g.fireRate()) + " выстр./мин");
        lore.add("<gray>Магазин: <white>" + g.magazine() + " <dark_gray>· <gray>" + ammoName(g));
        StringBuilder modes = new StringBuilder();
        for (GunSpec.FireMode fm : g.modes()) modes.append(modes.isEmpty() ? "" : "<dark_gray>, ").append(g.label(fm));
        lore.add("<gray>Режимы: " + modes);
        lore.add("");
        lore.add("<dark_gray>" + (fireLeft ? "ЛКМ" : "ПКМ") + " — огонь" + (g.modes().size() > 1 ? ", F — режим" : "")
                + (fireLeft ? ", ПКМ или Q — перезарядка" : ", Q — перезарядка"));
        if (g.zoom() > 0) lore.add("<dark_gray>Присед — прицел");
        m.lore(lore.stream().map(Msg::item).toList());
        m.setMaxStackSize(1);
        m.setItemModel(NamespacedKey.minecraft("crossbow"));
        if (g.model() > 0) {
            CustomModelDataComponent cmd = m.getCustomModelDataComponent();
            cmd.setFloats(List.of((float) g.model()));
            m.setCustomModelDataComponent(cmd);
        }
        m.getPersistentDataContainer().set(kGun, PersistentDataType.STRING, g.id());
        it.setItemMeta(m);
        return it;
    }

    /** Патроны. null, если такого типа нет. */
    public ItemStack ammo(String type, int count) {
        if (type != null && type.equalsIgnoreCase(GunSpec.AMMO_EXPLOSIVE)) return ru.mgp.util.ItemSpec.explosiveArrow(count, "default");
        AmmoSpec a = type == null ? null : ammo.get(type.toLowerCase(Locale.ROOT));
        if (a == null) return null;
        ItemStack it = new ItemStack(Material.PHANTOM_MEMBRANE, Math.max(1, Math.min(64, count)));
        ItemMeta m = it.getItemMeta();
        m.displayName(Msg.item(a.name()));
        m.lore(List.of(Msg.item("<dark_gray>Патроны для оружия")));
        if (a.model() > 0) {
            CustomModelDataComponent cmd = m.getCustomModelDataComponent();
            cmd.setFloats(List.of((float) a.model()));
            m.setCustomModelDataComponent(cmd);
        }
        m.getPersistentDataContainer().set(kAmmo, PersistentDataType.STRING, a.id());
        it.setItemMeta(m);
        return it;
    }

    /** Убирает UID экземпляра (копия из набора или магазина должна быть отдельным оружием). */
    public ItemStack fresh(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return it;
        ItemMeta m = it.getItemMeta();
        PersistentDataContainer pdc = m.getPersistentDataContainer();
        if (!pdc.has(kGun) || !pdc.has(kUid)) return it;
        pdc.remove(kUid);
        it.setItemMeta(m);
        return it;
    }

    public GunSpec spec(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return null;
        String id = it.getItemMeta().getPersistentDataContainer().get(kGun, PersistentDataType.STRING);
        return id == null ? null : guns.get(id);
    }

    private String ammoName(GunSpec g) {
        if (g.infiniteAmmo()) return "<white>∞";
        if (g.arrowAmmo()) return "<red>разрывные стрелы";
        AmmoSpec a = ammo.get(g.ammo().toLowerCase(Locale.ROOT));
        return a == null ? "<white>" + g.ammo() : a.name();
    }

    private static String fmt(double d) { return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.1f", d); }

    // ------------------------------------------------------------------ состояние экземпляра

    private UUID uid(ItemStack it, boolean create) {
        ItemMeta m = it.getItemMeta();
        String s = m.getPersistentDataContainer().get(kUid, PersistentDataType.STRING);
        if (s != null) try { return UUID.fromString(s); } catch (IllegalArgumentException ignored) {}
        if (!create) return null;
        UUID u = UUID.randomUUID();
        m.getPersistentDataContainer().set(kUid, PersistentDataType.STRING, u.toString());
        it.setItemMeta(m);
        return u;
    }

    private GunState state(UUID u, ItemStack it, GunSpec g) {
        GunState st = states.get(u);
        if (st != null) return st;
        st = new GunState();
        PersistentDataContainer pdc = it.getItemMeta().getPersistentDataContainer();
        Integer mag = pdc.get(kMag, PersistentDataType.INTEGER);
        Integer mode = pdc.get(kMode, PersistentDataType.INTEGER);
        String loaded = pdc.get(kLoaded, PersistentDataType.STRING);
        if (g.arrowAmmo()) {
            if (loaded != null && !loaded.isBlank()) for (String t : loaded.split(",")) if (!t.isBlank()) st.loaded.add(t);
            st.mag = st.loaded.size();
        } else st.mag = mag == null ? g.magazine() : Math.max(0, Math.min(g.magazine(), mag));
        st.mode = mode == null ? 0 : Math.floorMod(mode, g.modes().size());
        states.put(u, st);
        return st;
    }

    /** Записать магазин и режим в предмет (когда он не в руке или после перезарядки). */
    private void save(ItemStack it) {
        GunSpec g = spec(it);
        if (g == null) return;
        UUID u = uid(it, false);
        GunState st = u == null ? null : states.get(u);
        if (st == null) return;
        ItemMeta m = it.getItemMeta();
        PersistentDataContainer pdc = m.getPersistentDataContainer();
        pdc.set(kMag, PersistentDataType.INTEGER, st.mag);
        pdc.set(kMode, PersistentDataType.INTEGER, st.mode);
        if (g.arrowAmmo()) pdc.set(kLoaded, PersistentDataType.STRING, String.join(",", st.loaded));
        it.setItemMeta(m);
    }

    private void saveAll(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (spec(it) != null) save(it);
    }

    // ------------------------------------------------------------------ патроны в инвентаре

    private boolean isAmmo(ItemStack it, GunSpec g) {
        if (it == null || it.getType().isAir()) return false;
        if (g.arrowAmmo()) return it.getType() == Material.ARROW && Keys.explosive(it) != null;
        if (!it.hasItemMeta()) return false;
        String t = it.getItemMeta().getPersistentDataContainer().get(kAmmo, PersistentDataType.STRING);
        return t != null && t.equalsIgnoreCase(g.ammo());
    }

    private int countAmmo(Player p, GunSpec g) {
        if (g.infiniteAmmo()) return Integer.MAX_VALUE;
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) if (isAmmo(it, g)) n += it.getAmount();
        return n;
    }

    /** Забирает до n патронов; для разрывных стрел возвращает их типы (по одному на стрелу). */
    private List<String> takeAmmo(Player p, GunSpec g, int n) {
        List<String> out = new ArrayList<>();
        if (g.infiniteAmmo()) { for (int i = 0; i < n; i++) out.add("default"); return out; }
        PlayerInventory inv = p.getInventory();
        ItemStack[] c = inv.getContents();
        for (int i = 0; i < c.length && out.size() < n; i++) {
            ItemStack it = c[i];
            if (!isAmmo(it, g)) continue;
            String type = g.arrowAmmo() ? Keys.explosive(it) : g.ammo();
            int take = Math.min(n - out.size(), it.getAmount());
            for (int k = 0; k < take; k++) out.add(type);
            if (take >= it.getAmount()) inv.setItem(i, null);
            else it.setAmount(it.getAmount() - take);
        }
        return out;
    }

    // ------------------------------------------------------------------ ввод

    private Shooter shooter(Player p) { return shooters.computeIfAbsent(p.getUniqueId(), k -> new Shooter()); }

    /** Можно ли сейчас стрелять. */
    private boolean canShoot(Player p) {
        if (p.getGameMode() == GameMode.SPECTATOR || p.isDead()) return false;
        GameSession s = plugin.sessions().of(p);
        if (s != null) return s.isPlaying() && s.isAlive(p) && s.owns(p.getWorld());
        if (!onlyInGames) return true;
        return p.getGameMode() == GameMode.CREATIVE && p.hasPermission("mgp.admin");
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Action a = e.getAction();
        Player p = e.getPlayer();
        if (a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK) {
            ItemStack it = p.getInventory().getItemInMainHand();
            GunSpec g = spec(it);
            if (g == null) return;
            if (a == Action.LEFT_CLICK_BLOCK) e.setCancelled(true); // оружием блоки не ломаются
            if (fireLeft) trigger(p, it, g, clickHold);
            return;
        }
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = p.getInventory().getItemInMainHand();
        GunSpec g = spec(it);
        if (g == null) {
            // оружие во второй руке не стреляет и не мешает
            if (e.getHand() == org.bukkit.inventory.EquipmentSlot.OFF_HAND && spec(e.getItem()) != null) e.setCancelled(true);
            return;
        }
        e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        // двери, рычаги, кнопки открываются и с оружием в руке (в приседе — стреляем)
        if (a == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null && openable(e.getClickedBlock().getType())
                && !p.isSneaking()) return;
        e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        if (fireLeft) {
            // ПКМ — перезарядка (клиент повторяет ПКМ, пока его держат: считаем только новое нажатие)
            Shooter sh = shooter(p);
            boolean pressed = now - sh.lastUse > triggerHold;
            sh.lastUse = now;
            if (pressed) startReload(p, true);
            return;
        }
        trigger(p, it, g, triggerHold);
    }

    /** ЛКМ с оружием по мобу или игроку — выстрел вместо удара. */
    @EventHandler(priority = EventPriority.LOW)
    public void onMelee(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || e.getCause() != org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (current != null) return; // урон от нашей же пули
        ItemStack it = p.getInventory().getItemInMainHand();
        GunSpec g = spec(it);
        if (g == null) return;
        e.setCancelled(true);
        if (fireLeft) trigger(p, it, g, clickHold);
    }

    /** Пока ЛКМ держат на блоке, клиент «копает» и машет рукой каждый тик — очередь продолжается. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(io.papermc.paper.event.player.PlayerArmSwingEvent e) {
        if (!fireLeft || e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        Shooter sh = shooters.get(e.getPlayer().getUniqueId());
        if (sh == null || sh.holdUntil < now - 1 || sh.lastTrigger == now) return;
        if (spec(e.getPlayer().getInventory().getItemInMainHand()) == null) return;
        sh.holdUntil = Math.max(sh.holdUntil, now + 2);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockDamage(org.bukkit.event.block.BlockDamageEvent e) {
        if (spec(e.getItemInHand()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockBreak(org.bukkit.event.block.BlockBreakEvent e) {
        if (spec(e.getPlayer().getInventory().getItemInMainHand()) != null) e.setCancelled(true);
    }

    /** Нажатие спуска: hold — сколько тиков очередь продолжается без повторного сигнала. */
    private void trigger(Player p, ItemStack it, GunSpec g, int hold) {
        Shooter sh = shooter(p);
        if (sh.lastTrigger == now) return; // клик по блоку и в воздух приходят в одном тике
        boolean pressed = now > sh.holdUntil; // новое нажатие, а не удержание
        sh.lastTrigger = now;
        sh.holdUntil = now + hold;
        if (!canShoot(p)) return;
        UUID u = uid(it, true);
        GunState st = state(u, p.getInventory().getItemInMainHand(), g);
        if (sh.reloadGun != null) {
            // дробовик можно прервать выстрелом, если в нём что-то есть
            if (g.singleReload() && st.mag > 0 && u.equals(sh.reloadGun)) cancelReload(p, sh);
            else return;
        }
        if (now < sh.nextShot) return;
        GunSpec.FireMode mode = g.modes().get(st.mode);
        switch (mode) {
            case SEMI -> fire(p, g, u, st, sh);
            case AUTO -> fire(p, g, u, st, sh);
            case BURST -> {
                if (!pressed || sh.burstLeft > 0) return;
                sh.burstLeft = g.burst() - 1;
                if (fire(p, g, u, st, sh) && sh.burstLeft == 0) sh.nextShot += g.interval() * 2;
            }
        }
    }

    private static boolean openable(Material m) {
        return org.bukkit.Tag.DOORS.isTagged(m) || org.bukkit.Tag.TRAPDOORS.isTagged(m) || org.bukkit.Tag.FENCE_GATES.isTagged(m)
                || org.bukkit.Tag.BUTTONS.isTagged(m) || m == Material.LEVER;
    }

    /** F — сменить режим огня. */
    @EventHandler(priority = EventPriority.LOW)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        ItemStack it = p.getInventory().getItemInMainHand();
        GunSpec g = spec(it);
        if (g == null) {
            if (spec(p.getInventory().getItemInOffHand()) != null) e.setCancelled(true);
            return;
        }
        e.setCancelled(true);
        if (g.modes().size() < 2) {
            p.sendActionBar(Msg.parse("<gray>У этого оружия один режим огня"));
            return;
        }
        UUID u = uid(it, true);
        GunState st = state(u, p.getInventory().getItemInMainHand(), g);
        st.mode = (st.mode + 1) % g.modes().size();
        Shooter sh = shooter(p);
        sh.burstLeft = 0;
        sh.lastHud = null;
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.PLAYERS, 0.6f, 1.8f);
        Msg.title(p, "", g.label(g.modes().get(st.mode)));
    }

    /** Q — перезарядка (оружие не выбрасывается). */
    @EventHandler(priority = EventPriority.LOW)
    public void onDrop(PlayerDropItemEvent e) {
        if (spec(e.getItemDrop().getItemStack()) == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) startReload(p, true); });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        ItemStack prev = p.getInventory().getItem(e.getPreviousSlot());
        if (spec(prev) != null) save(prev);
        Shooter sh = shooters.get(p.getUniqueId());
        if (sh != null) {
            if (sh.reloadGun != null) cancelReload(p, sh);
            sh.burstLeft = 0;
            sh.lastHud = null;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        saveAll(e.getPlayer());
        Shooter sh = shooters.remove(e.getPlayer().getUniqueId());
        if (sh != null && sh.zoomed) e.getPlayer().removePotionEffect(PotionEffectType.SLOWNESS);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onKnockback(EntityKnockbackEvent e) {
        Shot s = current;
        if (s == null || !e.getEntity().equals(s.target())) return;
        if (s.knockback() <= 0) e.setCancelled(true);
        else e.setKnockback(e.getKnockback().multiply(s.knockback()));
    }

    // ------------------------------------------------------------------ тик: очереди, перезарядка, прицел, HUD

    private void tick() {
        now++;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Shooter sh = shooters.get(p.getUniqueId());
            ItemStack it = p.getInventory().getItemInMainHand();
            GunSpec g = spec(it);
            if (g == null) {
                if (sh != null) {
                    if (sh.zoomed) unzoom(p, sh);
                    if (sh.reloadGun != null) sh.reloadGun = null;
                    if (sh.lastHud != null) { sh.lastHud = null; if (hud) p.sendActionBar(net.kyori.adventure.text.Component.empty()); }
                }
                continue;
            }
            if (sh == null) sh = shooter(p);
            UUID u = uid(it, false);
            GunState st = u == null ? null : states.get(u);
            if (st == null) { u = uid(it, true); st = state(u, p.getInventory().getItemInMainHand(), g); it = p.getInventory().getItemInMainHand(); }

            // перезарядка
            if (sh.reloadGun != null) {
                if (!sh.reloadGun.equals(u)) cancelReload(p, sh);
                else if (now >= sh.reloadEnd) finishReload(p, g, st, sh);
            }

            // прицел снайперки
            boolean wantZoom = g.zoom() > 0 && p.isSneaking() && sh.reloadGun == null && canShoot(p);
            if (wantZoom) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 6, g.zoom() + 1, false, false, false));
                sh.zoomed = true;
            } else if (sh.zoomed) unzoom(p, sh);

            // очередь и отсечка
            if (sh.reloadGun == null && canShoot(p)) {
                if (sh.burstLeft > 0) {
                    while (sh.burstLeft > 0 && sh.nextShot <= now) {
                        sh.burstLeft--;
                        if (!fire(p, g, u, st, sh)) break;
                        if (sh.burstLeft == 0) sh.nextShot += g.interval() * 2; // пауза после отсечки
                    }
                } else if (g.modes().get(st.mode) == GunSpec.FireMode.AUTO && now <= sh.holdUntil) {
                    int guard = 0;
                    while (sh.nextShot <= now && guard++ < 10) if (!fire(p, g, u, st, sh)) break;
                }
            } else sh.burstLeft = 0;

            if (hud) hud(p, g, st, sh);
        }
    }

    private void unzoom(Player p, Shooter sh) {
        sh.zoomed = false;
        PotionEffect pe = p.getPotionEffect(PotionEffectType.SLOWNESS);
        if (pe != null && pe.getDuration() <= 6 && !pe.hasParticles()) p.removePotionEffect(PotionEffectType.SLOWNESS);
    }

    private void hud(Player p, GunSpec g, GunState st, Shooter sh) {
        String line;
        if (sh.reloadGun != null) {
            double k = Math.min(1.0, (now - sh.reloadStart) / (double) Math.max(1, sh.reloadEnd - sh.reloadStart));
            int filled = (int) Math.round(k * 10);
            line = "<yellow>Перезарядка <green>" + "▮".repeat(filled) + "<dark_gray>" + "▮".repeat(10 - filled)
                    + " <white>" + st.mag + "<gray>/" + g.magazine();
        } else {
            int reserve = countAmmo(p, g);
            String mag = st.mag == 0 ? "<red>0" : st.mag <= Math.max(1, g.magazine() / 4) ? "<gold>" + st.mag : "<white>" + st.mag;
            line = g.name() + " <dark_gray>| " + g.label(g.modes().get(st.mode)) + " <dark_gray>| " + mag + "<gray>/" + g.magazine()
                    + " <dark_gray>[" + (g.infiniteAmmo() ? "∞" : reserve) + "]";
            if (st.mag == 0) line += reserve > 0 ?  (fireLeft ? " <yellow>ПКМ — перезарядка" : " <yellow>Q — перезарядка") : " <red>нет патронов";
        }
        if (line.equals(sh.lastHud) && now % 30 != 0) return;
        sh.lastHud = line;
        p.sendActionBar(Msg.parse(line));
    }

    // ------------------------------------------------------------------ перезарядка

    private void startReload(Player p, boolean manual) {
        ItemStack it = p.getInventory().getItemInMainHand();
        GunSpec g = spec(it);
        if (g == null || !canShoot(p)) return;
        Shooter sh = shooter(p);
        if (sh.reloadGun != null) return;
        UUID u = uid(it, true);
        GunState st = state(u, p.getInventory().getItemInMainHand(), g);
        if (st.mag >= g.magazine()) { if (manual) p.sendActionBar(Msg.parse("<gray>Магазин полон")); return; }
        if (countAmmo(p, g) <= 0) {
            p.sendActionBar(Msg.parse("<red>Нет патронов: " + ammoName(g)));
            sound(p.getLocation(), "outofammoclick", Sound.BLOCK_DISPENSER_FAIL, 0.8f, 1.2f);
            return;
        }
        sh.burstLeft = 0;
        sh.reloadGun = u;
        sh.reloadStart = now;
        sh.reloadEnd = now + Math.max(1, Math.round(g.reload() * 20));
        sh.lastHud = null;
        sound(p.getLocation(), g.reloadSound(), Sound.ITEM_CROSSBOW_LOADING_MIDDLE, 0.9f, 1f);
    }

    private void finishReload(Player p, GunSpec g, GunState st, Shooter sh) {
        int need = g.singleReload() ? 1 : g.magazine() - st.mag;
        List<String> got = takeAmmo(p, g, Math.max(0, need));
        st.mag += got.size();
        if (g.arrowAmmo()) st.loaded.addAll(got);
        sh.reloadGun = null;
        sh.lastHud = null;
        sh.nextShot = Math.max(sh.nextShot, now);
        if (g.singleReload() && st.mag < g.magazine() && countAmmo(p, g) > 0) {
            startReload(p, false); // следующий патрон
            return;
        }
        save(p.getInventory().getItemInMainHand());
        sound(p.getLocation(), "reloadmagclick", Sound.ITEM_CROSSBOW_LOADING_END, 0.7f, 1f);
    }

    private void cancelReload(Player p, Shooter sh) {
        sh.reloadGun = null;
        sh.lastHud = null;
    }

    // ------------------------------------------------------------------ выстрел

    /** Один выстрел. false — магазин пуст (очередь прекращается). */
    private boolean fire(Player p, GunSpec g, UUID u, GunState st, Shooter sh) {
        if (st.mag <= 0) {
            sh.burstLeft = 0;
            sh.nextShot = now + 4;
            sound(p.getLocation(), "outofammoclick", Sound.BLOCK_DISPENSER_FAIL, 0.8f, 1.4f);
            startReload(p, false);
            return false;
        }
        st.mag--;
        sh.nextShot = Math.max(sh.nextShot, now - 1) + g.interval();
        if (sh.nextShot < now) sh.nextShot = now;
        boolean aiming = p.isSneaking();
        double spread = aiming ? g.aimSpread() : g.spread();
        if (!p.isOnGround() || p.isSprinting()) spread = spread * 1.5 + 1;
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        Random r = java.util.concurrent.ThreadLocalRandom.current();

        if (g.explosive()) {
            // стрелы несут свой тип взрыва, у ракет и гранат он задан в explosion
            String type = g.arrowAmmo() ? (st.loaded.isEmpty() ? "default" : st.loaded.pollFirst()) : g.explosionType();
            Vector v = spreadDir(dir, spread, r).multiply(g.speed());
            ItemStack arrowItem = Keys.explosive(new ItemStack(Material.ARROW), type);
            p.launchProjectile(Arrow.class, v, a -> {
                a.setItemStack(arrowItem);
                a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                a.setCritical(false);
                if (!g.gravity()) a.setGravity(false);
            });
        } else {
            GameSession s = plugin.sessions().of(p);
            for (int i = 0; i < g.pellets(); i++) bullet(p, s, g, eye, spreadDir(dir, spread, r), i == 0);
        }

        sound(eye, g.sound(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 3f, 0.9f + r.nextFloat() * 0.2f);
        eye.getWorld().spawnParticle(Particle.SMOKE, eye.clone().add(dir.clone().multiply(0.8)), 2, 0.02, 0.02, 0.02, 0.005);
        recoil(p, g, aiming, r);
        return true;
    }

    private static Vector spreadDir(Vector dir, double degrees, Random r) {
        Vector d = dir.clone().normalize();
        if (degrees <= 0) return d;
        double rad = Math.toRadians(degrees) * Math.sqrt(r.nextDouble());
        double ang = r.nextDouble() * Math.PI * 2;
        Vector up = Math.abs(d.getY()) > 0.99 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        Vector a = d.getCrossProduct(up).normalize();
        Vector b = a.getCrossProduct(d).normalize();
        return d.multiply(Math.cos(rad)).add(a.multiply(Math.sin(rad) * Math.cos(ang))).add(b.multiply(Math.sin(rad) * Math.sin(ang))).normalize();
    }

    private void bullet(Player p, GameSession s, GunSpec g, Location eye, Vector dir, boolean effects) {
        World w = eye.getWorld();
        String myTeam = s == null ? null : s.logic().zoneTeam(p);
        RayTraceResult hit = w.rayTrace(eye, dir, g.range(), FluidCollisionMode.NEVER, true, 0.05, e -> {
            if (e.equals(p) || !(e instanceof LivingEntity le) || le.isDead() || e.isInvulnerable()) return false;
            if (e instanceof org.bukkit.entity.ArmorStand) return false;
            if (e instanceof Player o) {
                if (o.getGameMode() == GameMode.SPECTATOR) return false;
                if (s != null && (!s.isAlive(o))) return false;
                if (myTeam != null && myTeam.equals(s.logic().zoneTeam(o))) return false; // пули проходят сквозь союзников
            }
            return true;
        });
        Vector end = hit != null ? hit.getHitPosition() : eye.toVector().add(dir.clone().multiply(g.range()));
        if (tracer) {
            double len = end.distance(eye.toVector());
            for (double d = tracerStart; d < len; d += 1.4)
                w.spawnParticle(Particle.CRIT, eye.toVector().add(dir.clone().multiply(d)).toLocation(w), 1, 0, 0, 0, 0);
        }
        if (hit == null) return;
        if (hit.getHitEntity() instanceof LivingEntity le) {
            var box = le.getBoundingBox();
            boolean head = hit.getHitPosition().getY() >= box.getMaxY() - box.getHeight() * 0.22;
            double dmg = g.damage() * (head ? g.headshot() : 1) * g.falloff(hit.getHitPosition().distance(eye.toVector()));
            Shot prev = current;
            current = new Shot(le, g.knockback());
            try {
                le.setNoDamageTicks(0);
                le.damage(dmg, DamageSource.builder(DamageType.ARROW).withCausingEntity(p).withDirectEntity(p)
                        .withDamageLocation(eye).build());
            } finally {
                current = prev;
            }
            Location at = hit.getHitPosition().toLocation(w);
            w.spawnParticle(Particle.DAMAGE_INDICATOR, at, 1, 0.05, 0.05, 0.05, 0);
            if (effects) p.playSound(p.getLocation(), head ? Sound.ENTITY_ARROW_HIT_PLAYER : Sound.ENTITY_ARROW_HIT,
                    SoundCategory.PLAYERS, 0.5f, head ? 1.4f : 1.0f);
        } else if (hit.getHitBlock() != null) {
            Block b = hit.getHitBlock();
            Location at = hit.getHitPosition().toLocation(w);
            w.spawnParticle(Particle.BLOCK, at, 4, 0.05, 0.05, 0.05, 0, b.getBlockData());
            if (breakGlass && b.getType().name().contains("GLASS") && (s == null || s.isPlaying())) {
                w.playSound(at, Sound.BLOCK_GLASS_BREAK, SoundCategory.BLOCKS, 1f, 1f);
                b.setType(Material.AIR);
            }
        }
    }

    private void recoil(Player p, GunSpec g, boolean aiming, Random r) {
        double k = g.recoil() * (aiming ? 0.6 : 1.0);
        if (k == 0) return;
        // относительный поворот: клиент прибавляет его к своему взгляду, без рывков при движении мыши и без телепорта
        float pitch = p.getLocation().getPitch();
        float dPitch = (float) -Math.min(k, pitch + 90); // не выше зенита
        float dYaw = (float) ((r.nextDouble() - 0.5) * k * 0.6);
        p.setRotation(io.papermc.paper.math.Angle.relative(dYaw), io.papermc.paper.math.Angle.relative(dPitch));
    }

    /** Звук из пака тем, у кого он загружен, остальным — ванильная замена. */
    private void sound(Location at, String key, Sound fallback, float volume, float pitch) {
        boolean vanilla = key == null || key.isBlank() || (key.startsWith("minecraft:") && key.contains("."));
        double r = 16 * Math.max(1, volume);
        for (Player o : at.getWorld().getPlayers()) {
            if (o.getLocation().distanceSquared(at) > r * r) continue;
            if (vanilla && key != null && !key.isBlank()) o.playSound(at, key, SoundCategory.PLAYERS, volume, pitch);
            else if (!vanilla && pack.loaded(o)) o.playSound(at, key, SoundCategory.PLAYERS, volume, pitch);
            else o.playSound(at, fallback, SoundCategory.PLAYERS, Math.min(volume, 1.5f), pitch + 0.4f);
        }
    }

    public void shutdown() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            saveAll(p);
            Shooter sh = shooters.get(p.getUniqueId());
            if (sh != null && sh.zoomed) unzoom(p, sh);
        }
    }
}
