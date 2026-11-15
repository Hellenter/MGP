package ru.mgp.editor;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import ru.mgp.util.ItemSpec;
import ru.mgp.util.Msg;
import ru.mgp.util.Pos;

import java.util.List;
import java.util.Locale;

/**
 * «Проверить на себе»: выполняет действия скрипта на админе прямо в мире, где он стоит.
 * Ничего не меняет в мире: setblock показывается только этому игроку, команды и действия режима не выполняются.
 */
final class ActionPreview {
    private ActionPreview() {}

    static void run(ScriptWand wand, Player p, List<String> actions) {
        if (actions.isEmpty()) { Msg.send(p, "<gray>У скрипта пока нет действий."); return; }
        for (int i = 0; i < actions.size(); i++) {
            String raw = actions.get(i);
            String type = ActionInfo.type(raw), arg = ActionInfo.arg(raw);
            if (type.equals("wait")) {
                List<String> rest = List.copyOf(actions.subList(i + 1, actions.size()));
                long ticks;
                try {
                    ticks = Math.max(1, Math.round(Double.parseDouble(arg.replace(',', '.')) * 20));
                } catch (NumberFormatException e) {
                    ticks = 20;
                }
                Bukkit.getScheduler().runTaskLater(wand.plugin(), () -> { if (p.isOnline()) run(wand, p, rest); }, ticks);
                return;
            }
            try {
                one(p, type, arg);
            } catch (Exception e) {
                Msg.send(p, "<red>Ошибка в действии <white>" + Msg.esc(raw) + "<red>: " + Msg.esc(String.valueOf(e.getMessage())));
            }
        }
    }

    private static void one(Player p, String type, String arg) {
        switch (type) {
            case "teleport", "tp" -> {
                Pos pos = Pos.parse(arg);
                Location to = pos.toLocation(p.getWorld());
                if (arg.trim().split("[\\s,]+").length < 5) { to.setYaw(p.getLocation().getYaw()); to.setPitch(p.getLocation().getPitch()); }
                p.getWorld().spawnParticle(Particle.REVERSE_PORTAL, p.getLocation().add(0, 1, 0), 30, 0.4, 0.4, 0.4, 0);
                p.teleport(to);
                p.setFallDistance(0);
                p.playSound(to, org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
            }
            case "message", "broadcast" -> p.sendMessage(Msg.parse(arg));
            case "title" -> {
                String[] parts = arg.split("\\|", 2);
                Msg.title(p, parts[0].trim(), parts.length > 1 ? parts[1].trim() : "");
            }
            case "actionbar" -> p.sendActionBar(Msg.parse(arg));
            case "sound" -> p.playSound(Sound.sound(Key.key(arg.split("\\s+")[0]), Sound.Source.MASTER, 1f, 1f));
            case "effect" -> {
                String[] a = arg.split("\\s+");
                PotionEffectType et = RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT)
                        .get(NamespacedKey.minecraft(a[0].toLowerCase(Locale.ROOT)));
                if (et == null) throw new IllegalArgumentException("неизвестный эффект " + a[0]);
                int amp = a.length > 1 ? Integer.parseInt(a[1]) : 0;
                // в проверке бесконечный эффект ограничиваем 10 секундами
                int ticks = a.length > 2 ? Integer.parseInt(a[2]) * 20 : 200;
                p.addPotionEffect(new PotionEffect(et, ticks, amp));
            }
            case "clear-effects" -> p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
            case "heal" -> { p.setHealth(Math.max(1, p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue())); p.setFoodLevel(20); }
            case "give" -> p.getInventory().addItem(ItemSpec.parse(arg).build());
            case "particle", "particles" -> {
                String[] a = arg.split("\\s+");
                Particle pt = Particle.valueOf(a[0].toUpperCase(Locale.ROOT));
                int count = a.length > 1 ? Integer.parseInt(a[1]) : 20;
                Location at = a.length >= 5 ? new Location(p.getWorld(), Double.parseDouble(a[2]), Double.parseDouble(a[3]), Double.parseDouble(a[4]))
                        : p.getLocation().add(0, 1, 0);
                if (pt.getDataType() == Void.class) p.spawnParticle(pt, at, count, 0.4, 0.4, 0.4, 0);
            }
            case "setblock" -> {
                String[] a = arg.split("\\s+");
                Material m = Material.matchMaterial(a[3]);
                if (m == null || !m.isBlock()) throw new IllegalArgumentException("неизвестный блок " + a[3]);
                Location at = new Location(p.getWorld(), Math.floor(Double.parseDouble(a[0])), Math.floor(Double.parseDouble(a[1])), Math.floor(Double.parseDouble(a[2])));
                p.sendBlockChange(at, m.createBlockData()); // только на экране админа
                p.sendActionBar(Msg.parse("<gray>Блок показан только тебе — на карте ничего не изменилось"));
            }
            default -> p.sendMessage(Msg.parse("<gray>«" + Msg.esc(type) + "» проверяется только в настоящей игре."));
        }
    }
}
