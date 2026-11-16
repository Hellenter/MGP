package ru.mgp.listener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import ru.mgp.MGPPlugin;
import ru.mgp.session.GameSession;

/**
 * Общие правила для всех сессий и передача событий в логику режима.
 * Вне игры (ожидание/итоги) и для наблюдателей почти всё запрещено.
 */
public final class GameListener implements Listener {
    private final MGPPlugin plugin;

    private static final java.util.Set<String> NATURAL_REASONS = java.util.Set.of(
            "NATURAL", "CHUNK_GEN", "PATROL", "RAID", "REINFORCEMENTS", "VILLAGE_DEFENSE",
            "VILLAGE_INVASION", "TRAP", "JOCKEY", "MOUNT");

    public GameListener(MGPPlugin plugin) { this.plugin = plugin; }

    private GameSession of(Player p) { return plugin.sessions().of(p); }

    /** true — игрок участвует в идущей игре и жив. */
    private boolean active(GameSession s, Player p) { return s != null && s.isPlaying() && s.isAlive(p); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) {
            // наблюдатели и игроки вне игры не могут бить мобов/торговцев
            Player d = damager(e);
            if (d != null) {
                GameSession ds = of(d);
                if (ds != null && !active(ds, d)) e.setCancelled(true);
            }
            return;
        }
        GameSession s = of(p);
        if (s == null) return;
        if (!active(s, p)) {
            e.setCancelled(true);
            if (e.getCause() == EntityDamageEvent.DamageCause.VOID) {
                if (s.isPlaying() || s.state() == GameSession.State.ENDING) p.teleport(s.spectatorLocation());
                else if (s.waitingWorld() != null) p.teleport(s.waitingSpawn().toLocation(s.waitingWorld()));
            }
            return;
        }
        Player d = damager(e);
        if (d != null && !active(s, d)) { e.setCancelled(true); return; }
        s.logic().onDamage(e);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        GameSession s = of(e.getPlayer());
        if (active(s, e.getPlayer())) s.logic().onDeath(e);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        GameSession s = of(p);
        if (s == null || s.gameWorld() == null) return;
        org.bukkit.Location custom = s.isPlaying() && s.isAlive(p) ? s.logic().respawnLocation(p) : null;
        e.setRespawnLocation(custom != null ? custom : s.spectatorLocation());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (s.isSpectator(p.getUniqueId())) s.makeSpectator(p);
        });
    }

    @EventHandler
    public void onWorldChange(org.bukkit.event.player.PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (of(p) == null) return;
        // клиент мог получить режим игры из пакета смены мира до того, как сервер его поменял
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && of(p) != null) ru.mgp.util.PlayerUtil.resyncGameMode(p);
        }, 2L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(org.bukkit.event.inventory.InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        GameSession s = of(p);
        if (s != null && s.isPlaying() && s.isAlive(p)) s.onContainerOpen(e.getInventory());
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(org.bukkit.event.entity.EntityExplodeEvent e) {
        GameSession s = plugin.sessions().byWorld(e.getEntity().getWorld());
        if (s != null && s.isPlaying()) s.logic().onExplode(e);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(org.bukkit.event.block.BlockExplodeEvent e) {
        GameSession s = plugin.sessions().byWorld(e.getBlock().getWorld());
        if (s != null && s.isPlaying()) s.logic().onBlockExplode(e);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        GameSession s = of(e.getPlayer());
        if (s == null) return;
        if (!active(s, e.getPlayer())) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(ru.mgp.util.Msg.parse("<red>Ломать можно только живым игрокам во время игры"));
        } else s.logic().onBlockBreak(e);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        GameSession s = of(e.getPlayer());
        if (s == null) return;
        if (!active(s, e.getPlayer())) e.setCancelled(true);
        else s.logic().onBlockPlace(e);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        GameSession s = of(e.getPlayer());
        if (s == null) return;
        if (!active(s, e.getPlayer())) e.setCancelled(true);
        else s.logic().onBucket(e);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        GameSession s = plugin.sessions().byWorld(e.getBlock().getWorld());
        if (s != null && s.isPlaying()) s.logic().onFlow(e);
    }

    /**
     * Огонь в мирах MGP не уничтожает карту: блоки не сгорают, пламя не перекидывается и не возникает
     * от лавы/молнии. Поджечь зажигалкой можно (игроков это жжёт), но постройка останется целой.
     * Вернуть ванильный огонь в режиме: settings.fire-spread: true.
     */
    private boolean fireProtected(org.bukkit.World w) {
        GameSession s = plugin.sessions().byWorld(w);
        if (s != null) return !s.mode.settings.getBoolean("fire-spread", false);
        return w.getName().startsWith(ru.mgp.world.WorldService.PREFIX);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(org.bukkit.event.block.BlockBurnEvent e) {
        if (fireProtected(e.getBlock().getWorld())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(org.bukkit.event.block.BlockIgniteEvent e) {
        var cause = e.getCause();
        if (cause == org.bukkit.event.block.BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL
                || cause == org.bukkit.event.block.BlockIgniteEvent.IgniteCause.FIREBALL
                || cause == org.bukkit.event.block.BlockIgniteEvent.IgniteCause.ARROW) return;
        if (fireProtected(e.getBlock().getWorld())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFireSpread(org.bukkit.event.block.BlockSpreadEvent e) {
        if (e.getSource().getType() == org.bukkit.Material.FIRE && fireProtected(e.getBlock().getWorld())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent e) {
        GameSession s = plugin.sessions().byWorld(e.getLocation().getWorld());
        if (s == null) return;
        String reason = e.getSpawnReason().name();
        if (reason.equals("CUSTOM") || reason.equals("COMMAND")) return; // торговцы и мобы из команд
        if (NATURAL_REASONS.contains(reason) && !s.mode.naturalMobs) { e.setCancelled(true); return; }
        if (s.isPlaying()) s.logic().onCreatureSpawn(e);
        else e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        GameSession s = of(p);
        if (s == null) return;
        if (active(s, p)) {
            s.zone().onMove(e);
            s.logic().onMove(e);
        } else if (s.waitingWorld() != null && p.getWorld().equals(s.waitingWorld()) && e.getTo().getY() < s.waitingVoidY()) {
            e.setTo(s.waitingSpawn().toLocation(s.waitingWorld()));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(org.bukkit.event.player.PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        GameSession s = of(p);
        if (active(s, p)) s.zone().onTeleport(e);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        GameSession s = of(p);
        if (s == null) return;
        if (!active(s, p) || s.mode.settings.getBoolean("disable-hunger", false)) e.setCancelled(true);
    }

    /** settings.no-regen: здоровье само не восстанавливается (сытость, зелья и т.п.). */
    @EventHandler(ignoreCancelled = true)
    public void onRegen(org.bukkit.event.entity.EntityRegainHealthEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        GameSession s = of(p);
        if (s != null && s.isPlaying() && s.mode.settings.getBoolean("no-regen", false)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p) {
            GameSession s = of(p);
            if (s != null && !active(s, p)) e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        GameSession s = of(e.getPlayer());
        if (s != null && !active(s, e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEvent e) {
        GameSession s = of(e.getPlayer());
        if (s == null) return;
        if (!active(s, e.getPlayer())) {
            if (s.isPlaying() || s.state() == GameSession.State.ENDING) e.setCancelled(true); // наблюдатели
            return;
        }
        s.scripts().onInteract(e);
        s.logic().onInteract(e);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        GameSession s = of(e.getPlayer());
        if (s != null && !active(s, e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttackEntity(io.papermc.paper.event.player.PrePlayerAttackEntityEvent e) {
        GameSession s = of(e.getPlayer());
        if (s == null) return;
        if (!active(s, e.getPlayer())) { e.setCancelled(true); return; }
        s.logic().onAttackEntity(e);
    }

    private static Player damager(EntityDamageEvent e) {
        if (!(e instanceof EntityDamageByEntityEvent ee)) return null;
        Entity d = ee.getDamager();
        if (d instanceof Player p) return p;
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }
}
