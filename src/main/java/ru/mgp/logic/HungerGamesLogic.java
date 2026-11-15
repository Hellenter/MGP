package ru.mgp.logic;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import ru.mgp.session.GameSession;
import ru.mgp.util.Cfg;
import ru.mgp.util.Msg;
import ru.mgp.util.PlayerUtil;

import java.util.*;

/**
 * Голодные игры: игроки на спавнах, заморозка перед стартом, период без PvP,
 * сундуки по тирам, последний выживший побеждает.
 */
public final class HungerGamesLogic extends GameLogic {
    private final int freeze;
    private final int grace;
    private final boolean allowPlace;
    private final Set<Material> breakable = EnumSet.noneOf(Material.class);
    private final String timeUp;
    private final Map<UUID, UUID> lastAttacker = new HashMap<>();
    private final Map<UUID, Long> lastAttackAt = new HashMap<>();
    private int freezeLeft;
    private boolean pvp;

    public HungerGamesLogic(GameSession s) {
        super(s);
        var st = s.mode.settings;
        freeze = st.getInt("freeze", 10);
        grace = st.getInt("grace-period", 30);
        allowPlace = st.getBoolean("allow-place", true);
        timeUp = st.getString("time-up", "draw");
        for (String m : st.getStringList("breakable-blocks")) {
            Material mat = Cfg.material(m, null);
            if (mat != null) breakable.add(mat);
        }
    }

    @Override
    public void onStart() {
        session.spreadToSpawns(session.map().spawns);
        for (Player p : session.alivePlayers()) {
            PlayerUtil.reset(p, GameMode.SURVIVAL);
            session.equip(p);
            Msg.title(p, "<gold>Голодные игры", freeze > 0 ? "<yellow>Старт через " + freeze + "..." : "<red>В бой!");
        }
        freezeLeft = freeze;
        pvp = grace <= 0 && freeze <= 0;
    }

    @Override
    public void onTick() {
        if (freezeLeft > 0) {
            freezeLeft--;
            for (Player p : session.alivePlayers()) {
                if (freezeLeft == 0) {
                    Msg.title(p, "<red><bold>В БОЙ!", grace > 0 ? "<gray>PvP через " + grace + " сек." : "");
                    p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1f);
                } else if (freezeLeft <= 5) {
                    p.sendActionBar(Msg.parse("<yellow>Старт через <red>" + freezeLeft));
                }
            }
        }
        if (!pvp && freezeLeft == 0 && session.elapsed() >= freeze + grace) {
            pvp = true;
            session.broadcast("<red><bold>PvP включено!");
        }
    }

    @Override
    public void onMove(PlayerMoveEvent e) {
        if (freezeLeft <= 0) return;
        Location from = e.getFrom(), to = e.getTo();
        if (from.getX() != to.getX() || from.getZ() != to.getZ()) {
            Location back = from.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            e.setTo(back);
        }
    }

    @Override
    public void onDamage(EntityDamageEvent e) {
        Player victim = (Player) e.getEntity();
        if (freezeLeft > 0) { e.setCancelled(true); return; }
        Player attacker = attacker(e);
        if (attacker != null && attacker != victim) {
            if (!pvp) { e.setCancelled(true); return; }
            lastAttacker.put(victim.getUniqueId(), attacker.getUniqueId());
            lastAttackAt.put(victim.getUniqueId(), System.currentTimeMillis());
        }
        boolean lethal = e.getCause() == EntityDamageEvent.DamageCause.VOID
                || (e.getFinalDamage() >= victim.getHealth() && !hasTotem(victim));
        if (!lethal) return;
        e.setCancelled(true);
        Player killer = attacker != null && attacker != victim ? attacker : recentAttacker(victim);
        kill(victim, killer);
    }

    /** Фоллбек на случай настоящей смерти (например, /kill). */
    @Override
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getPlayer();
        e.deathMessage(null);
        Player killer = victim.getKiller() != null ? victim.getKiller() : recentAttacker(victim);
        announceDeath(victim, killer);
        session.eliminate(victim);
        checkWin();
    }

    private void kill(Player victim, Player killer) {
        Location loc = victim.getLocation();
        for (ItemStack it : victim.getInventory().getContents()) {
            if (it != null && !it.getType().isAir()) loc.getWorld().dropItemNaturally(loc, it);
        }
        victim.getInventory().clear();
        announceDeath(victim, killer);
        victim.playSound(victim.getLocation(), Sound.ENTITY_PLAYER_DEATH, 1f, 1f);
        session.eliminate(victim);
        Msg.title(victim, "<red><bold>ТЫ ВЫБЫЛ", "<gray>Теперь ты наблюдатель");
        checkWin();
    }

    private void announceDeath(Player victim, Player killer) {
        if (killer != null && session.isAlive(killer)) {
            session.addKill(killer.getUniqueId());
            killer.playSound(killer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            session.broadcast("<red>" + Msg.esc(victim.getName()) + " <gray>убит игроком <green>" + Msg.esc(killer.getName()));
        } else {
            session.broadcast("<red>" + Msg.esc(victim.getName()) + " <gray>погиб.");
        }
        session.broadcast("<gray>Осталось игроков: <yellow>" + Math.max(0, session.aliveIds().size() - 1));
    }

    @Override
    public void onLeave(Player p, boolean wasAlive) {
        if (!wasAlive) return;
        session.broadcast("<red>" + Msg.esc(p.getName()) + " <gray>покинул игру.");
        checkWin();
    }

    private void checkWin() {
        if (!session.isPlaying()) return;
        if (session.aliveIds().size() <= 1) session.end(new ArrayList<>(session.aliveIds()), null);
    }

    @Override
    public void onTimeUp() {
        if ("most-kills".equalsIgnoreCase(timeUp)) {
            UUID best = null;
            for (UUID u : session.aliveIds()) if (best == null || session.kills(u) > session.kills(best)) best = u;
            session.end(best == null ? List.of() : List.of(best), "<yellow>Время вышло! Побеждает лучший по убийствам.");
        } else {
            session.end(List.of(), "<yellow>Время вышло! Ничья.");
        }
    }

    @Override
    public void onBlockBreak(BlockBreakEvent e) {
        if (!breakable.contains(e.getBlock().getType())) e.setCancelled(true);
    }

    @Override
    public void onBlockPlace(BlockPlaceEvent e) {
        if (!allowPlace) e.setCancelled(true);
    }

    @Override
    public boolean handleAction(String action, Player target) {
        switch (action.trim().toLowerCase(Locale.ROOT)) {
            case "deathmatch" -> {
                session.spreadToSpawns(session.map().spawns);
                session.broadcast("<dark_red><bold>ДЕЗМАТЧ! <red>Все перенесены на спавны.");
                return true;
            }
            case "pvp-on" -> { pvp = true; return true; }
            case "pvp-off" -> { pvp = false; return true; }
            default -> { return false; }
        }
    }

    @Override
    public void placeholders(Player p, Map<String, String> ph) {
        int left = freeze + grace - session.elapsed();
        ph.put("pvp", pvp ? "<red>вкл" : "<green>через " + Msg.time(left));
    }

    private Player recentAttacker(Player victim) {
        UUID u = lastAttacker.get(victim.getUniqueId());
        Long at = lastAttackAt.get(victim.getUniqueId());
        if (u == null || at == null || System.currentTimeMillis() - at > 15_000) return null;
        return org.bukkit.Bukkit.getPlayer(u);
    }

    private static Player attacker(EntityDamageEvent e) {
        if (!(e instanceof EntityDamageByEntityEvent ee)) return null;
        Entity d = ee.getDamager();
        if (d instanceof Player p) return p;
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    private static boolean hasTotem(Player p) {
        return p.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || p.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING;
    }
}
