package ru.mgp.session;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import ru.mgp.MGPPlugin;
import ru.mgp.ui.Hotbar;
import ru.mgp.util.Cfg;
import ru.mgp.util.PlayerUtil;
import ru.mgp.util.Pos;

/** Главное лобби (хаб): точка появления, предметы, сброс игрока. */
public final class HubService {
    private final MGPPlugin plugin;

    public HubService(MGPPlugin plugin) { this.plugin = plugin; }

    public Location location() {
        World w = Bukkit.getWorld(plugin.getConfig().getString("hub.world", "world"));
        if (w == null) w = Bukkit.getWorlds().get(0);
        Pos pos = Pos.parse(plugin.getConfig().getString("hub.spawn"));
        return pos != null ? pos.toLocation(w) : w.getSpawnLocation();
    }

    public World world() { return location().getWorld(); }

    public void send(Player p) {
        GameMode gm = Cfg.enumValue(GameMode.class, plugin.getConfig().getString("hub.gamemode"), GameMode.ADVENTURE);
        PlayerUtil.reset(p, gm);
        // шкалы режимов (крепость в осаде, цели, скрипты) в хабе не нужны — даже если режим не успел их убрать
        java.util.List<net.kyori.adventure.bossbar.BossBar> bars = new java.util.ArrayList<>();
        p.activeBossBars().forEach(bars::add);
        bars.forEach(p::hideBossBar);
        p.teleport(location());
        Hotbar.giveHub(p);
        plugin.visibility().refresh(p);
        // После перехода в другой мир клиент может «вернуть» старые эффекты/режим: чистим ещё раз чуть позже.
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || plugin.sessions().of(p) != null) return;
            for (org.bukkit.potion.PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
            p.setFireTicks(0);
            org.bukkit.attribute.AttributeInstance max = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            if (max != null) {
                max.setBaseValue(20.0); // max-health из режима меняет базовое значение
                p.setHealth(Math.min(p.getHealth(), max.getValue()));
            }
            PlayerUtil.resyncGameMode(p);
        }, 3L);
    }
}
