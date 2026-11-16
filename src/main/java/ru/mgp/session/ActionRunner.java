package ru.mgp.session;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import ru.mgp.util.ItemSpec;
import ru.mgp.util.Msg;

import java.util.*;

/**
 * Универсальные действия для таймерных событий, триггеров, модификаторов и китов.
 * Формат строки: "тип: аргументы". Если target != null — действия над игроками применяются только к нему.
 *
 *   message: <текст>             — сообщение (MiniMessage, плейсхолдеры {..})
 *   title: <заголовок> | <подзаголовок>
 *   actionbar: <текст>
 *   sound: minecraft:entity.ender_dragon.growl
 *   time: 18000 [lock]           — время суток (lock — зафиксировать)
 *   weather: clear | rain | thunder
 *   gamerule: <имя> <значение>
 *   effect: speed 1 [секунды]    — эффект (без секунд — бесконечный)
 *   clear-effects
 *   give: ARROW 16
 *   heal
 *   max-health: 30
 *   border: <размер> [секунды]   — изменить барьер мира
 *   refill-chests [tier]
 *   command: <консольная команда, {player} — имя игрока>
 *   mode: <действие режима>      — например mode: deathmatch
 *   teleport: x y z [yaw pitch]  — телепорт в мире игры (без yaw/pitch — взгляд не меняется)
 *   particle: <тип> [кол-во] [x y z] — частицы у игрока или в точке
 *   setblock: x y z <блок>       — поставить блок в мире игры (AIR — убрать)
 *   wait: <сек>                  — пауза: следующие действия выполнятся позже
 *   spawns: enable|disable <группа> — открыть или закрыть группу спавнов (spawn-points с group)
 */
public final class ActionRunner {
    private ActionRunner() {}

    public static void run(GameSession s, List<String> actions, Player target) {
        if (actions == null) return;
        for (int n = 0; n < actions.size(); n++) {
            String raw = actions.get(n);
            // wait: <сек> — остальные действия выполнятся позже (если игра ещё идёт и игрок в ней)
            if (raw.trim().toLowerCase(Locale.ROOT).startsWith("wait")) {
                double sec;
                try {
                    sec = Double.parseDouble(raw.substring(raw.indexOf(':') + 1).trim().replace(',', '.'));
                } catch (Exception e) {
                    s.plugin().getLogger().warning("Ошибка в действии '" + raw + "': ожидалось wait: <секунды>");
                    continue;
                }
                List<String> rest = List.copyOf(actions.subList(n + 1, actions.size()));
                Bukkit.getScheduler().runTaskLater(s.plugin(), () -> {
                    if (!s.isPlaying()) return;
                    if (target != null && (!target.isOnline() || !s.isAlive(target))) return;
                    run(s, rest, target);
                }, Math.max(1L, Math.round(sec * 20)));
                return;
            }
            try {
                runOne(s, raw, target);
            } catch (Exception e) {
                s.plugin().getLogger().warning("Ошибка в действии '" + raw + "': " + e.getMessage());
            }
        }
    }

    private static void runOne(GameSession s, String raw, Player target) {
        int i = raw.indexOf(':');
        // "minecraft:" внутри аргумента не должен ломать разбор: тип — до первого двоеточия
        String type = (i < 0 ? raw : raw.substring(0, i)).trim().toLowerCase(Locale.ROOT);
        String arg = i < 0 ? "" : raw.substring(i + 1).trim();
        List<Player> targets = target != null ? List.of(target) : s.online();
        List<Player> aliveTargets = target != null ? List.of(target) : s.alivePlayers();
        World world = s.gameWorld();

        switch (type) {
            case "message" -> targets.forEach(p -> p.sendMessage(Msg.parse(arg, s.placeholders(p))));
            case "broadcast" -> s.online().forEach(p -> p.sendMessage(Msg.parse(arg, s.placeholders(p))));
            case "title" -> {
                String[] parts = arg.split("\\|", 2);
                targets.forEach(p -> Msg.title(p, Msg.apply(parts[0].trim(), s.placeholders(p)),
                        parts.length > 1 ? Msg.apply(parts[1].trim(), s.placeholders(p)) : ""));
            }
            case "actionbar" -> targets.forEach(p -> p.sendActionBar(Msg.parse(arg, s.placeholders(p))));
            case "sound" -> {
                Sound snd = Sound.sound(Key.key(arg.split("\\s+")[0]), Sound.Source.MASTER, 1f, 1f);
                targets.forEach(p -> p.playSound(snd));
            }
            case "time" -> {
                String[] p = arg.split("\\s+");
                s.setTime(Long.parseLong(p[0]), p.length > 1 && p[1].equalsIgnoreCase("lock"));
            }
            case "weather" -> s.setWeather(arg);
            case "gamerule" -> {
                String[] p = arg.split("\\s+", 2);
                if (world != null) s.plugin().worlds().applyGamerules(world, Map.of(p[0], p[1]));
            }
            case "effect" -> {
                String[] p = arg.split("\\s+");
                PotionEffectType et = RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT)
                        .get(NamespacedKey.minecraft(p[0].toLowerCase(Locale.ROOT)));
                if (et == null) throw new IllegalArgumentException("неизвестный эффект " + p[0]);
                int amp = p.length > 1 ? Integer.parseInt(p[1]) : 0;
                int ticks = p.length > 2 ? Integer.parseInt(p[2]) * 20 : PotionEffect.INFINITE_DURATION;
                aliveTargets.forEach(pl -> pl.addPotionEffect(new PotionEffect(et, ticks, amp)));
            }
            case "clear-effects" -> aliveTargets.forEach(pl ->
                    pl.getActivePotionEffects().forEach(e -> pl.removePotionEffect(e.getType())));
            case "give" -> {
                ItemSpec spec = ItemSpec.parse(arg);
                aliveTargets.forEach(pl -> pl.getInventory().addItem(spec.build()));
            }
            case "heal" -> aliveTargets.forEach(pl -> {
                AttributeInstance a = pl.getAttribute(Attribute.MAX_HEALTH);
                pl.setHealth(a != null ? a.getValue() : 20);
                pl.setFoodLevel(20);
            });
            case "max-health" -> aliveTargets.forEach(pl -> {
                AttributeInstance a = pl.getAttribute(Attribute.MAX_HEALTH);
                if (a != null) { a.setBaseValue(Double.parseDouble(arg)); pl.setHealth(a.getValue()); }
            });
            case "border" -> {
                if (world == null) return;
                String[] p = arg.split("\\s+");
                double size = Double.parseDouble(p[0]);
                long secs = p.length > 1 ? Long.parseLong(p[1]) : 0;
                if (secs > 0) world.getWorldBorder().changeSize(size, secs * 20L);
                else world.getWorldBorder().setSize(size);
            }
            case "refill-chests" -> s.fillChests(arg.isEmpty() ? null : arg);
            case "command" -> {
                if (arg.contains("{player}")) {
                    for (Player p : targets) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), arg.replace("{player}", p.getName()));
                } else {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), arg);
                }
            }
            case "teleport", "tp" -> {
                if (world == null) return;
                String[] t = arg.split("[\\s,]+");
                ru.mgp.util.Pos pos = ru.mgp.util.Pos.parse(arg);
                for (Player pl : aliveTargets) {
                    org.bukkit.Location to = pos.toLocation(world);
                    if (t.length < 5) { to.setYaw(pl.getLocation().getYaw()); to.setPitch(pl.getLocation().getPitch()); }
                    MapScripts.particle(world, "REVERSE_PORTAL", pl.getLocation().add(0, 1, 0), 30, 0.4);
                    pl.teleport(to);
                    pl.setFallDistance(0);
                    MapScripts.particle(world, "PORTAL", to.clone().add(0, 1, 0), 40, 0.4);
                    world.playSound(to, org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                }
            }
            case "particle", "particles" -> {
                if (world == null) return;
                String[] t = arg.split("\\s+");
                int count = t.length > 1 ? Integer.parseInt(t[1]) : 20;
                if (t.length >= 5) {
                    MapScripts.particle(world, t[0], new org.bukkit.Location(world, Double.parseDouble(t[2]), Double.parseDouble(t[3]), Double.parseDouble(t[4])), count, 0.4);
                } else {
                    for (Player pl : aliveTargets) MapScripts.particle(world, t[0], pl.getLocation().add(0, 1, 0), count, 0.4);
                }
            }
            case "setblock" -> {
                if (world == null) return;
                String[] t = arg.split("\\s+");
                org.bukkit.Material m = org.bukkit.Material.matchMaterial(t[3]);
                if (m == null || !m.isBlock()) throw new IllegalArgumentException("неизвестный блок " + t[3]);
                world.getBlockAt((int) Math.floor(Double.parseDouble(t[0])), (int) Math.floor(Double.parseDouble(t[1])),
                        (int) Math.floor(Double.parseDouble(t[2]))).setType(m);
            }
            case "spawns" -> {
                // spawns: enable <группа> | disable <группа>
                String[] t = arg.trim().split("\\s+");
                if (t.length < 2) throw new IllegalArgumentException("ожидалось spawns: enable|disable <группа>");
                boolean on = switch (t[0].toLowerCase(Locale.ROOT)) {
                    case "enable", "on", "open" -> true;
                    case "disable", "off", "close" -> false;
                    default -> throw new IllegalArgumentException("ожидалось enable или disable, а не " + t[0]);
                };
                s.setSpawnGroup(t[1], on);
            }
            case "mode" -> {
                if (!s.logic().handleAction(arg, target))
                    s.plugin().getLogger().warning("Режим " + s.mode.type + " не знает действие: " + arg);
            }
            default -> s.plugin().getLogger().warning("Неизвестное действие: " + raw);
        }
    }
}
