package ru.mgp.session;

import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.persistence.PersistentDataType;
import ru.mgp.MGPPlugin;
import ru.mgp.config.ModeConfig;
import ru.mgp.util.Cfg;
import ru.mgp.util.Keys;

import java.util.*;

/**
 * Разрывные стрелы: стрела с меткой (ItemSpec explosive / EXPLOSIVE_ARROW) взрывается при попадании
 * в блок, игрока или моба — из лука, арбалета (и мультивыстрела) или раздатчика.
 * Работает только в идущей игре режима, где settings.explosive-arrows.enabled: true; иначе это обычная стрела.
 * Профиль взрыва — settings.explosive-arrows, отдельные типы — explosive-arrows.types.<тип> (что не указано, берётся из основного).
 */
public final class ExplosiveArrows implements Listener {
    /** Настройки одного типа разрывной стрелы. */
    record Profile(float power, boolean fire, boolean breakBlocks, boolean dropBlocks, Set<Material> protectedBlocks,
                   double damage, double selfDamage, double teamDamage, double mobDamage, double maxDamage,
                   double knockback, boolean onPlayers, boolean onEntities, boolean onBlocks, boolean trail) {}

    /** Взрыв, который идёт прямо сейчас (createExplosion синхронный — урон и события приходят внутри вызова). */
    private record Blast(GameSession session, Profile profile, Player shooter) {}

    private final MGPPlugin plugin;
    private final NamespacedKey done;
    private final Map<UUID, Particle> flying = new HashMap<>();
    private Blast current;

    public ExplosiveArrows(MGPPlugin plugin) {
        this.plugin = plugin;
        this.done = new NamespacedKey(plugin, "exploded");
        Bukkit.getScheduler().runTaskTimer(plugin, this::trails, 2L, 2L);
    }

    // ------------------------------------------------------------------ профиль из режима

    static Profile profile(ModeConfig mode, String type) {
        ConfigurationSection base = mode.settings.getConfigurationSection("explosive-arrows");
        if (base == null || !base.getBoolean("enabled", false)) return null;
        ConfigurationSection t = type == null || type.equals("default") ? null : base.getConfigurationSection("types." + type);
        if (t != null && !t.getBoolean("enabled", true)) return null;
        Set<Material> prot = Cfg.materials(pick(t, base, "protected-blocks") instanceof List<?> l
                ? l.stream().map(String::valueOf).toList() : List.of("BEDROCK", "BARRIER"), new ArrayList<>());
        return new Profile(
                (float) Math.max(0.1, num(t, base, "power", 2.0)),
                bool(t, base, "fire", false),
                bool(t, base, "break-blocks", true),
                bool(t, base, "drop-blocks", false),
                prot,
                Math.max(0, num(t, base, "damage", 1.0)),
                Math.max(0, num(t, base, "self-damage", 0.5)),
                Math.max(0, num(t, base, "team-damage", 0.0)),
                Math.max(0, num(t, base, "mob-damage", 1.0)),
                Math.max(0, num(t, base, "max-damage", 0)),
                Math.max(0, num(t, base, "knockback", 1.0)),
                bool(t, base, "on-players", true),
                bool(t, base, "on-entities", true),
                bool(t, base, "on-blocks", true),
                bool(t, base, "trail", true));
    }

    private static Object pick(ConfigurationSection t, ConfigurationSection base, String key) {
        if (t != null && t.contains(key)) return t.get(key);
        return base.get(key);
    }

    private static double num(ConfigurationSection t, ConfigurationSection base, String key, double def) {
        Object o = pick(t, base, key);
        if (o instanceof Number n) return n.doubleValue();
        try { return o == null ? def : Double.parseDouble(String.valueOf(o).trim()); } catch (NumberFormatException e) { return def; }
    }

    private static boolean bool(ConfigurationSection t, ConfigurationSection base, String key, boolean def) {
        Object o = pick(t, base, key);
        return o == null ? def : Boolean.parseBoolean(String.valueOf(o).trim());
    }

    /** Сессия, в мире которой летит стрела, если в ней сейчас идёт игра и взрывы разрешены. */
    private GameSession session(World w) {
        GameSession s = plugin.sessions().byWorld(w);
        return s != null && s.isPlaying() && s.logic().explosivesAllowed() ? s : null;
    }

    // ------------------------------------------------------------------ полёт

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof AbstractArrow arrow)) return;
        String type = Keys.explosive(arrow.getItemStack());
        if (type == null) return;
        GameSession s = session(arrow.getWorld());
        Profile p = s == null ? null : profile(s.mode, type);
        if (p != null && p.trail()) flying.put(arrow.getUniqueId(), Particle.SMOKE);
    }

    private void trails() {
        if (flying.isEmpty()) return;
        flying.entrySet().removeIf(en -> {
            Entity ent = Bukkit.getEntity(en.getKey());
            if (!(ent instanceof AbstractArrow a) || !a.isValid() || a.isInBlock()) return true;
            a.getWorld().spawnParticle(en.getValue(), a.getLocation(), 2, 0.05, 0.05, 0.05, 0.01);
            return false;
        });
    }

    // ------------------------------------------------------------------ попадание

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof AbstractArrow arrow)) return;
        String type = Keys.explosive(arrow.getItemStack());
        if (type == null || arrow.getPersistentDataContainer().has(done)) return;
        GameSession s = session(arrow.getWorld());
        Profile p = s == null ? null : profile(s.mode, type);
        if (p == null) return;
        Entity hit = e.getHitEntity();
        if (hit != null ? (hit instanceof Player ? !p.onPlayers() : !p.onEntities()) : !p.onBlocks()) return;
        arrow.getPersistentDataContainer().set(done, PersistentDataType.BYTE, (byte) 1);
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        flying.remove(arrow.getUniqueId());
        Location at = arrow.getLocation().clone();
        Player shooter = arrow.getShooter() instanceof Player pl ? pl : null;
        // взрыв тиком позже: сначала проходит урон самой стрелы, потом взрыв (с обнулёнными кадрами неуязвимости)
        // точка взрыва: центр задетого существа или место, где стрела воткнулась (в событии она ещё до точки попадания)
        Bukkit.getScheduler().runTask(plugin, () -> {
            Location where = at;
            if (hit != null && hit.isValid() && hit.getWorld().equals(at.getWorld())) where = hit.getBoundingBox().getCenter().toLocation(at.getWorld());
            else if (hit == null && arrow.isValid()) where = arrow.getLocation();
            arrow.remove();
            if (!s.isPlaying()) return;
            if (hit instanceof LivingEntity le && le.isValid() && !le.isDead()) le.setNoDamageTicks(0);
            explode(s, p, where, shooter);
        });
    }

    private void explode(GameSession s, Profile p, Location at, Player shooter) {
        Player source = shooter != null && shooter.isOnline() && s.isAlive(shooter) && shooter.getWorld().equals(at.getWorld()) ? shooter : null;
        Blast prev = current;
        current = new Blast(s, p, source);
        try {
            if (source != null) at.getWorld().createExplosion(source, at, p.power(), p.fire(), p.breakBlocks(), false);
            else at.getWorld().createExplosion(at, p.power(), p.fire(), p.breakBlocks(), null);
        } finally {
            current = prev;
        }
    }

    // ------------------------------------------------------------------ последствия взрыва

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        Blast b = current;
        if (b == null) return;
        EntityDamageEvent.DamageCause c = e.getCause();
        if (c != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION && c != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) return;
        Profile p = b.profile();
        double mult;
        if (e.getEntity() instanceof Player v) {
            if (b.shooter() != null && v.equals(b.shooter())) mult = p.selfDamage();
            else if (teammates(b.session(), b.shooter(), v)) mult = p.teamDamage();
            else mult = p.damage();
        } else mult = p.mobDamage();
        if (mult <= 0) { e.setCancelled(true); return; }
        double d = e.getDamage() * mult;
        if (p.maxDamage() > 0) d = Math.min(d, p.maxDamage());
        e.setDamage(d);
    }

    private static boolean teammates(GameSession s, Player a, Player b) {
        if (a == null) return false;
        String ta = s.logic().zoneTeam(a), tb = s.logic().zoneTeam(b);
        return ta != null && ta.equals(tb);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onKnockback(EntityKnockbackEvent e) {
        Blast b = current;
        if (b == null || e.getCause() != EntityKnockbackEvent.Cause.EXPLOSION) return;
        double k = b.profile().knockback();
        if (k <= 0) e.setCancelled(true);
        else if (k != 1) e.setKnockback(e.getKnockback().multiply(k));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (current != null) blocks(e.blockList(), current.profile(), e::setYield);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (current != null) blocks(e.blockList(), current.profile(), e::setYield);
    }

    private static void blocks(List<Block> list, Profile p, java.util.function.Consumer<Float> yield) {
        if (!p.breakBlocks()) { list.clear(); return; }
        list.removeIf(b -> p.protectedBlocks().contains(b.getType()));
        if (!p.dropBlocks()) yield.accept(0f);
    }
}
