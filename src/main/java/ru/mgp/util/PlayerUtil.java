package ru.mgp.util;

import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

public final class PlayerUtil {
    private PlayerUtil() {}

    /** Полный сброс состояния игрока при переходе между лобби/игрой. */
    public static void reset(Player p, GameMode mode) {
        p.closeInventory();
        p.getInventory().clear();
        p.setItemOnCursor(null);
        p.setGameMode(mode);
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(max != null ? max.getValue() : 20.0);
        p.setFoodLevel(20);
        p.setSaturation(5f);
        p.setExhaustion(0f);
        p.setLevel(0);
        p.setExp(0f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        p.setGlowing(false);
        p.setAllowFlight(false);
        p.setFlying(false);
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
    }
}
