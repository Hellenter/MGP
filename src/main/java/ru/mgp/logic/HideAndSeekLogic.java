package ru.mgp.logic;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.key.Key;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import ru.mgp.session.GameSession;
import ru.mgp.ui.Menu;
import ru.mgp.util.*;

import java.util.*;

/**
 * Прятки. variant: classic — прячутся сами игроки; prophunt — прячущиеся превращаются в блоки.
 * Ищущие сначала ждут (ослеплены и стоят) hide-time секунд, потом ищут. Пойманный прячущийся
 * становится ищущим (caught: seeker) или наблюдателем (caught: spectator).
 * Ищущие ловят всех — победа ищущих; дожили до конца времени — победа прячущихся.
 * Таунты прячущихся (звук + частицы, дают очки) и провокации ищущих (заставляют прячущихся себя выдать).
 * Ники над головами скрыты всем (settings.hide-nametags).
 */
public final class HideAndSeekLogic extends GameLogic {
    private enum Role { HIDER, SEEKER }

    /**
     * Таунт прячущегося или провокация ищущего из settings.taunts / settings.provocations.
     * target: all | nearest (только ближайший к ищущему прячущийся); max-hiders / max-time-left — условия
     * доступности провокации (прячущихся осталось не больше N; до конца игры не больше N сек.), 0 — без условия.
     */
    private record Effect(String id, String display, Material icon, String sound, float volume, float pitch,
                          String particle, int cooldown, int points, String type, int duration, List<String> lore,
                          String target, int maxHiders, int maxTimeLeft) {}

    private static final NamespacedKey SPEED_KEY = new NamespacedKey("mgp", "seeker_speed");

    private final boolean prop;
    private final int hideTime;
    private final boolean caughtBecomeSeeker;
    private final int hitsToKill;
    private final double seekerDamage;
    private final double tauntHeal;
    private final int tauntCooldown;
    private final double seekerSpeed;
    private final boolean provocationItems;
    private final String weaponName;
    private final double missPenalty;
    private final int autoTaunt;
    private final Set<Material> disguiseAllowed = EnumSet.noneOf(Material.class);
    private final Material seekerWeapon;
    private final List<Effect> taunts = new ArrayList<>();
    private final List<Effect> provocations = new ArrayList<>();

    private final Map<UUID, Role> roles = new HashMap<>();
    private final Set<UUID> firstSeekers = new HashSet<>();
    private final Map<UUID, Integer> points = new HashMap<>();
    private final Map<String, Long> cooldowns = new HashMap<>(); // uuid:effect -> время окончания
    private final Map<UUID, BlockDisplay> displays = new HashMap<>();
    private final Map<UUID, Interaction> hitboxes = new HashMap<>();
    private final Map<UUID, BlockData> disguise = new HashMap<>();
    private final Map<UUID, Long> lastHitboxHit = new HashMap<>();
    private int hideLeft;
    private int nextAutoTaunt;

    public HideAndSeekLogic(GameSession s) {
        super(s);
        var st = s.mode.settings;
        prop = "prophunt".equalsIgnoreCase(st.getString("variant", "classic")) || "prop_hunt".equalsIgnoreCase(st.getString("variant", ""));
        hideTime = Math.max(0, st.getInt("hide-time", 45));
        caughtBecomeSeeker = !"spectator".equalsIgnoreCase(st.getString("caught", "seeker"));
        // hits-to-kill: сколько полных ударов ловит прячущегося (0 — по урону оружия); старый one-hit: true = 1
        int htk = st.getInt("hits-to-kill", prop ? 0 : 3);
        if (!st.contains("hits-to-kill") && st.getBoolean("one-hit", false)) htk = 1;
        hitsToKill = Math.max(0, htk);
        seekerDamage = Math.max(0, st.getDouble("seeker-damage", prop ? 0 : 9)); // HP за полный удар, без критов; 0 — hits-to-kill
        tauntHeal = Math.max(0, st.getDouble("taunt-heal", prop ? 0 : 2));       // HP прячущемуся за таунт
        tauntCooldown = Math.max(0, st.getInt("taunt-cooldown", prop ? 0 : 40)); // общая перезарядка всех таунтов, сек.
        seekerSpeed = st.getDouble("seeker-speed", prop ? 0 : 5);
        provocationItems = st.getBoolean("provocation-items", true);
        weaponName = st.getString("seeker-weapon-name", prop ? "<red><bold>Оружие ищущего" : "<red><bold>Нож");
        missPenalty = st.getDouble("miss-penalty", prop ? 1.0 : 0.0);
        autoTaunt = Math.max(0, st.getInt("auto-taunt", 60));
        seekerWeapon = Cfg.material(st.getString("seeker-weapon", "IRON_SWORD"), Material.IRON_SWORD);
        List<String> unknown = new ArrayList<>();
        disguiseAllowed.addAll(Cfg.materials(st.getStringList("disguise-blocks"), unknown));
        if (!unknown.isEmpty()) s.plugin().getLogger().warning("disguise-blocks в " + s.mode.id + ": не распознано " + unknown);
        loadEffects(st.getList("taunts"), taunts, "sound");
        loadEffects(st.getList("provocations"), provocations, "sound");
    }

    private void loadEffects(List<?> raw, List<Effect> out, String defType) {
        if (raw == null) return;
        for (Object o : raw) {
            Map<String, Object> m = Cfg.asMap(o);
            if (m == null) continue;
            String id = Cfg.str(m, "id", "t" + out.size());
            out.add(new Effect(id, Cfg.str(m, "display", id), Cfg.material(Cfg.str(m, "icon", "NOTE_BLOCK"), Material.NOTE_BLOCK),
                    Cfg.str(m, "sound", null), (float) Cfg.dbl(m, "volume", 2.0), (float) Cfg.dbl(m, "pitch", 1.0),
                    Cfg.str(m, "particle", null), Cfg.integer(m, "cooldown", 15), Cfg.integer(m, "points", 1),
                    Cfg.str(m, "type", defType).toLowerCase(Locale.ROOT), Cfg.integer(m, "duration", 3), Cfg.strings(m.get("lore")),
                    Cfg.str(m, "target", "all").toLowerCase(Locale.ROOT), Cfg.integer(m, "max-hiders", 0), Cfg.integer(m, "max-time-left", 0)));
        }
    }

    // ------------------------------------------------------------------ старт

    @Override
    public void onStart() {
        List<Player> all = new ArrayList<>(session.alivePlayers());
        if (all.size() < 2 && !session.solo()) { // например, /mgp start в одиночку: ни прятаться, ни искать
            session.end(List.of(), "<red>Для пряток нужно хотя бы 2 игрока.");
            return;
        }
        Collections.shuffle(all, session.random());
        var st = session.mode.settings;
        int n = Math.max(st.getInt("seekers", 1), (int) Math.round(all.size() * st.getDouble("seeker-ratio", 0)));
        n = Math.max(1, Math.min(n, all.size() - 1));
        for (int i = 0; i < all.size(); i++) {
            Player p = all.get(i);
            roles.put(p.getUniqueId(), i < n ? Role.SEEKER : Role.HIDER);
            if (i < n) firstSeekers.add(p.getUniqueId());
            PlayerUtil.reset(p, GameMode.ADVENTURE); // до телепорта в другой мир (см. HungerGamesLogic)
        }
        // прячущиеся — на спавны hider (или общие), ищущие — в комнату seeker-spawn или на спавны seeker
        Pos seekerSpawn = Pos.parse(session.map().yaml.getString("seeker-spawn"));
        List<Player> hidersList = new ArrayList<>(), seekersList = new ArrayList<>();
        for (Player p : all) (roles.get(p.getUniqueId()) == Role.SEEKER ? seekersList : hidersList).add(p);
        session.spreadToSpawns(hidersList, "hider");
        if (seekerSpawn == null) session.spreadToSpawns(seekersList, "seeker");
        hideLeft = hideTime;
        nextAutoTaunt = hideTime + autoTaunt;
        for (Player p : all) {
            if (isSeeker(p)) {
                if (seekerSpawn != null) p.teleport(seekerSpawn.toLocation(session.gameWorld()));
                giveSeeker(p);
                if (hideLeft > 0) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, (hideLeft + 1) * 20, 0, false, false));
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (hideLeft + 1) * 20, 6, false, false));
                }
                Msg.title(p, "<red><bold>ТЫ ИЩЕШЬ", hideLeft > 0 ? "<gray>Ждём " + hideLeft + " сек., пока все спрячутся" : "<gray>Найди всех!");
            } else {
                giveHider(p);
                if (prop) disguise(p, startDisguise(p));
                Msg.title(p, "<green><bold>ТЫ ПРЯЧЕШЬСЯ", prop ? "<gray>ПКМ предметом по блоку — стать им" : "<gray>Спрячься за " + hideLeft + " сек.!");
            }
        }
        session.broadcast("<gray>Ищущие: <red>" + names(Role.SEEKER) + "<gray>. Прячущихся: <green>" + count(Role.HIDER));
        session.plugin().visibility().refreshAll(session);
    }

    private String names(Role r) {
        List<String> out = new ArrayList<>();
        for (Player p : session.alivePlayers()) if (roles.get(p.getUniqueId()) == r) out.add(Msg.esc(p.getName()));
        return String.join(", ", out);
    }

    private int count(Role r) {
        int c = 0;
        for (UUID u : session.aliveIds()) if (roles.get(u) == r) c++;
        return c;
    }

    private boolean isSeeker(Player p) { return roles.get(p.getUniqueId()) == Role.SEEKER; }

    @Override
    public String zoneTeam(Player p) {
        Role r = roles.get(p.getUniqueId());
        return r == null ? null : r == Role.SEEKER ? "seeker" : "hider";
    }
    private boolean isHider(Player p) { return roles.get(p.getUniqueId()) == Role.HIDER && session.isAlive(p); }

    private void giveSeeker(Player p) {
        var inv = p.getInventory();
        inv.clear();
        inv.setItem(0, Items.icon(seekerWeapon, weaponName, List.of(seekerDamage > 0 ? "<gray>Удар: " + hp(seekerDamage) + "❤"
                : hitsToKill == 1 ? "<gray>Один удар — и прячущийся пойман"
                : hitsToKill > 1 ? "<gray>Ловит с " + hitsToKill + " ударов" : "<gray>Бей прячущихся"), false));
        if (provocationItems) {
            int slot = 1; // каждая провокация — свой предмет, перезарядка видна на самом предмете
            for (Effect ef : provocations) {
                if (slot > 8) break;
                inv.setItem(slot++, Keys.tag(Items.icon(ef.icon(), ef.display() + " <gray>(ПКМ)", provokeLore(ef, -1), false), "logic:provoke:" + ef.id()));
            }
        } else if (!provocations.isEmpty()) {
            inv.setItem(8, Items.tagged(Material.GOAT_HORN, "<gold><bold>Провокации</bold> <gray>(ПКМ)", "logic:provoke",
                    "<gray>Заставь прячущихся выдать себя"));
        }
        inv.setHeldItemSlot(0);
        AttributeInstance ms = p.getAttribute(Attribute.MOVEMENT_SPEED);
        if (ms != null) {
            ms.removeModifier(SPEED_KEY);
            if (seekerSpeed != 0) ms.addModifier(new AttributeModifier(SPEED_KEY, seekerSpeed / 100.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        }
    }

    /** Описание провокации: что делает, условия, перезарядка (left — оставшиеся секунды, -1 — не показывать). */
    private List<String> provokeLore(Effect ef, long left) {
        List<String> lore = new ArrayList<>(ef.lore());
        if (!lore.isEmpty()) lore.add("");
        if ("nearest".equals(ef.target())) lore.add("<gray>Действует на ближайшего прячущегося");
        if (ef.maxHiders() > 0) lore.add("<gray>Когда прячущихся " + ef.maxHiders() + " или меньше");
        if (ef.maxTimeLeft() > 0) lore.add("<gray>За " + Msg.time(ef.maxTimeLeft()) + " до конца и позже");
        lore.add("<gray>Перезарядка: " + ef.cooldown() + " сек.");
        if (left >= 0) lore.add(left > 0 ? "<red>Ещё " + left + " сек." : "<green>▶ Нажми");
        return lore;
    }

    private void giveHider(Player p) {
        var inv = p.getInventory();
        inv.clear();
        if (prop) inv.setItem(0, Items.tagged(Material.BLAZE_ROD, "<aqua><bold>Маскировка</bold> <gray>(ПКМ по блоку)", "logic:disguise",
                "<gray>Посмотри на блок и нажми ПКМ —", "<gray>станешь таким же блоком"));
        if (!taunts.isEmpty()) inv.setItem(8, Items.tagged(Material.NOTE_BLOCK, "<light_purple><bold>Таунты</bold> <gray>(ПКМ)", "logic:taunt",
                "<gray>Подразни ищущих и получи очки"));
        inv.setHeldItemSlot(0);
    }

    // ------------------------------------------------------------------ таймер

    @Override
    public void onTick() {
        if (hideLeft > 0) {
            hideLeft--;
            for (Player p : session.alivePlayers()) {
                if (hideLeft == 0) {
                    if (isSeeker(p)) {
                        p.removePotionEffect(PotionEffectType.BLINDNESS);
                        p.removePotionEffect(PotionEffectType.SLOWNESS);
                        Msg.title(p, "<red><bold>ИЩИ!", "<gray>Прячущихся: " + count(Role.HIDER));
                        p.playSound(p.getLocation(), Sound.ENTITY_WOLF_GROWL, 1f, 0.8f);
                    } else {
                        Msg.title(p, "", "<red>Ищущие вышли на охоту!");
                        p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.4f);
                    }
                } else if (hideLeft <= 5 || hideLeft % 10 == 0) {
                    p.sendActionBar(Msg.parse((isSeeker(p) ? "<red>Выход через " : "<green>Прячься! Ищущие выйдут через ") + hideLeft + " сек."));
                }
            }
            if (hideLeft == 0) {
                Pos seekerSpawn = Pos.parse(session.map().yaml.getString("seeker-spawn"));
                if (seekerSpawn == null) return; // ищущие и так на своих спавнах
                // ищущие из отдельной комнаты выходят на спавны seeker (или общие)
                List<Player> seekersOut = new ArrayList<>();
                for (Player p : session.alivePlayers()) if (isSeeker(p)) seekersOut.add(p);
                session.spreadToSpawns(seekersOut, "seeker");
            }
            return;
        }
        if (autoTaunt > 0 && session.elapsed() >= nextAutoTaunt) {
            nextAutoTaunt = session.elapsed() + autoTaunt;
            for (Player p : session.alivePlayers()) if (isHider(p)) forcedNoise(p, null);
            session.broadcast("<gray>Прячущиеся невольно издали звук!");
        }
        for (Player p : session.alivePlayers()) {
            if (isHider(p) && prop) {
                BlockData d = disguise.get(p.getUniqueId());
                p.sendActionBar(Msg.parse("<aqua>Ты: <white>" + (d == null ? "-" : pretty(d.getMaterial())) + " <dark_gray>| <light_purple>очки: " + points.getOrDefault(p.getUniqueId(), 0)));
            }
        }
    }

    // ------------------------------------------------------------------ движение

    @Override
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (hideLeft > 0 && isSeeker(p)) {
            Location from = e.getFrom(), to = e.getTo();
            if (from.getX() != to.getX() || from.getZ() != to.getZ() || to.getY() > from.getY()) {
                Location back = from.clone();
                back.setYaw(to.getYaw());
                back.setPitch(to.getPitch());
                e.setTo(back);
            }
            return;
        }
        if (prop && isHider(p)) follow(p, e.getTo());
    }

    // ------------------------------------------------------------------ prop hunt: маскировка

    private BlockData startDisguise(Player p) {
        if (!disguiseAllowed.isEmpty()) {
            List<Material> l = new ArrayList<>(disguiseAllowed);
            Material m = l.get(session.random().nextInt(l.size()));
            if (m.isBlock()) return m.createBlockData();
        }
        Block under = p.getLocation().getBlock().getRelative(0, -1, 0);
        return allowed(under.getType()) ? under.getBlockData() : Material.OAK_LEAVES.createBlockData();
    }

    private boolean allowed(Material m) {
        if (m.isAir() || !m.isBlock() || m == Material.WATER || m == Material.LAVA || m == Material.BARRIER) return false;
        String n = m.name(); // блоки, которые BlockDisplay не рисует (их рисует блок-сущность)
        if (n.contains("CHEST") || n.endsWith("_BED") || n.endsWith("SIGN") || n.endsWith("BANNER") || n.endsWith("_HEAD")
                || n.endsWith("_SKULL") || n.endsWith("SHULKER_BOX") || n.equals("DECORATED_POT") || n.equals("CONDUIT")) return false;
        return disguiseAllowed.isEmpty() ? m.isSolid() : disguiseAllowed.contains(m);
    }

    private void disguise(Player p, BlockData data) {
        disguise.put(p.getUniqueId(), data);
        World w = p.getWorld();
        Location base = blockLoc(p.getLocation());
        BlockDisplay d = displays.get(p.getUniqueId());
        if (d == null || !d.isValid()) {
            d = w.spawn(base, BlockDisplay.class, x -> {
                x.setPersistent(false);
                x.setTeleportDuration(2);
            });
            displays.put(p.getUniqueId(), d);
        }
        d.setBlock(data);
        Interaction hb = hitboxes.get(p.getUniqueId());
        if (hb == null || !hb.isValid()) {
            hb = w.spawn(base.clone().add(0.5, 0, 0.5), Interaction.class, x -> {
                x.setPersistent(false);
                x.setInteractionWidth(1.0f);
                x.setInteractionHeight(1.0f);
                x.setResponsive(true);
            });
            hitboxes.put(p.getUniqueId(), hb);
        }
        // свой хитбокс прячущемуся не нужен: иначе его ПКМ попадает в собственный хитбокс, а не в блок
        p.hideEntity(session.plugin(), hb);
        session.plugin().visibility().refreshAll(session);
    }

    private void undisguise(UUID u) {
        BlockDisplay d = displays.remove(u);
        if (d != null) d.remove();
        Interaction hb = hitboxes.remove(u);
        if (hb != null) hb.remove();
        disguise.remove(u);
    }

    private static Location blockLoc(Location l) {
        return new Location(l.getWorld(), l.getBlockX(), Math.floor(l.getY() + 0.01), l.getBlockZ());
    }

    private void follow(Player p, Location to) {
        BlockDisplay d = displays.get(p.getUniqueId());
        Interaction hb = hitboxes.get(p.getUniqueId());
        if (d == null) return;
        Location b = blockLoc(to);
        if (d.getLocation().getBlockX() != b.getBlockX() || d.getLocation().getBlockY() != b.getBlockY() || d.getLocation().getBlockZ() != b.getBlockZ()) {
            d.teleport(b);
            if (hb != null) hb.teleport(b.clone().add(0.5, 0, 0.5));
        }
    }

    /** Прячущийся в prop hunt невидим для всех: вместо него — блок. */
    @Override
    public boolean canSee(Player viewer, Player target) {
        return !(prop && session.isPlaying() && isHider(target));
    }

    @Override
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        // ищущий бьёт обычный блок — штраф (prop hunt), чтобы не «прощупывали» всю карту
        if (prop && hideLeft == 0 && missPenalty > 0 && isSeeker(p) && e.getAction() == Action.LEFT_CLICK_BLOCK) {
            // взмах после удара по маскировке тоже приходит как клик по блоку за ней — это не промах
            Long hitAt = lastHitboxHit.get(p.getUniqueId());
            if (hitAt != null && System.currentTimeMillis() - hitAt < 250) return;
            p.setHealth(Math.max(1.0, p.getHealth() - missPenalty));
            p.sendActionBar(Msg.parse("<red>Это обычный блок! -" + (missPenalty / 2) + "❤"));
        }
    }

    /** Удар ищущего по блоку-маскировке (сущность Interaction). */
    @Override
    public void onAttackEntity(PrePlayerAttackEntityEvent e) {
        if (!(e.getAttacked() instanceof Interaction hb)) return;
        e.setCancelled(true);
        Player seeker = e.getPlayer();
        lastHitboxHit.put(seeker.getUniqueId(), System.currentTimeMillis());
        if (!isSeeker(seeker) || hideLeft > 0) return;
        for (Map.Entry<UUID, Interaction> en : hitboxes.entrySet()) {
            if (!en.getValue().equals(hb)) continue;
            Player hider = Bukkit.getPlayer(en.getKey());
            if (hider == null || !isHider(hider)) return;
            hit(seeker, hider, strengthOf(seeker));
            return;
        }
    }

    /** Сила удара 0.2..1 по заряду атаки, как в ванили: спам кликами почти не наносит урона. */
    private static double strengthOf(Player seeker) {
        float cd = seeker.getAttackCooldown();
        seeker.resetCooldown();
        return 0.2 + 0.8 * cd * cd;
    }

    private static double attackDamage(Player seeker) {
        AttributeInstance a = seeker.getAttribute(Attribute.ATTACK_DAMAGE);
        return a != null ? Math.max(1.0, a.getValue()) : 1.0;
    }

    private static double maxHealth(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        return a != null ? a.getValue() : 20.0;
    }

    // ------------------------------------------------------------------ урон и поимка

    @Override
    public void onDamage(EntityDamageEvent e) {
        Player victim = (Player) e.getEntity();
        e.setCancelled(true); // весь урон считаем сами
        if (!(e instanceof EntityDamageByEntityEvent ee) || !(ee.getDamager() instanceof Player attacker)) {
            if (e.getCause() == EntityDamageEvent.DamageCause.VOID) {
                if (isHider(victim)) caught(victim, null);
                else victim.teleport(isSeeker(victim) ? session.spawnFor("seeker") : session.spectatorLocation());
            }
            return;
        }
        if (hideLeft > 0 || !isSeeker(attacker) || !isHider(victim)) return;
        if (e.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) return; // взмах мечом по соседям не в счёт
        // заряд атаки к этому моменту уже сброшен — силу удара берём из ванильного урона
        hit(attacker, victim, Math.min(1.0, e.getDamage() / attackDamage(attacker)));
    }

    private void hit(Player seeker, Player hider, double strength) {
        double dmg;
        if (seekerDamage > 0 || hitsToKill > 0) {
            if (strength >= 0.9) strength = 1.0; // почти полный заряд считаем полным; критов нет (сила не больше 1)
            dmg = (seekerDamage > 0 ? seekerDamage : maxHealth(hider) / hitsToKill) * strength;
        } else {
            dmg = attackDamage(seeker) * strength;
        }
        World w = hider.getWorld();
        Location at = prop && displays.containsKey(hider.getUniqueId()) ? displays.get(hider.getUniqueId()).getLocation().add(0.5, 0.5, 0.5) : hider.getLocation().add(0, 1, 0);
        w.playSound(at, Sound.ENTITY_PLAYER_HURT, 1f, 1f);
        w.spawnParticle(Particle.DAMAGE_INDICATOR, at, 4, 0.2, 0.2, 0.2, 0.1);
        if ((seekerDamage <= 0 && hitsToKill == 1) || hider.getHealth() - dmg <= 0.05) { caught(hider, seeker); return; }
        hider.setHealth(hider.getHealth() - dmg);
        hider.playHurtAnimation(0);
        seeker.sendActionBar(Msg.parse("<red>Попал! <gray>Осталось " + hp(hider.getHealth()) + "❤"));
    }

    /** HP в сердцах для текста: 9 → «4.5», 2 → «1». */
    private static String hp(double health) {
        double h = health / 2;
        return h == Math.rint(h) ? String.valueOf((long) h) : String.format(Locale.ROOT, "%.1f", h);
    }

    private void caught(Player hider, Player seeker) {
        UUID u = hider.getUniqueId();
        undisguise(u);
        if (seeker != null) {
            session.addKill(seeker.getUniqueId());
            seeker.playSound(seeker.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            session.broadcast("<green>" + Msg.esc(hider.getName()) + " <gray>найден! Нашёл <red>" + Msg.esc(seeker.getName()));
        } else {
            session.broadcast("<green>" + Msg.esc(hider.getName()) + " <gray>выбыл.");
        }
        if (caughtBecomeSeeker && count(Role.HIDER) > 1) {
            roles.put(u, Role.SEEKER);
            PlayerUtil.reset(hider, GameMode.ADVENTURE);
            giveSeeker(hider);
            Msg.title(hider, "<red><bold>ТЕБЯ НАШЛИ", "<gray>Теперь ты ищущий");
            session.plugin().visibility().refreshAll(session);
        } else {
            roles.remove(u);
            session.eliminate(hider);
            Msg.title(hider, "<red><bold>ТЕБЯ НАШЛИ", "<gray>Теперь ты наблюдатель");
        }
        int left = count(Role.HIDER);
        if (left > 0) session.broadcast("<gray>Осталось прячущихся: <green>" + left);
        checkWin();
    }

    @Override
    public void onDeath(PlayerDeathEvent e) {
        e.deathMessage(null);
        Player p = e.getPlayer();
        if (isHider(p)) caught(p, null);
    }

    @Override
    public void onLeave(Player p, boolean wasAlive) {
        undisguise(p.getUniqueId());
        Role r = roles.remove(p.getUniqueId());
        if (wasAlive && r != null) session.broadcast("<gray>" + Msg.esc(p.getName()) + " вышел (" + (r == Role.SEEKER ? "ищущий" : "прячущийся") + ").");
        checkWin();
    }

    private void checkWin() {
        if (!session.isPlaying()) return;
        if (session.solo()) { // отладка: одному не с кем играть — заканчиваем, только когда никого не осталось
            if (session.aliveIds().isEmpty()) session.end(List.of(), null);
            return;
        }
        if (count(Role.HIDER) == 0) {
            Set<UUID> win = new HashSet<>();
            for (UUID u : session.aliveIds()) if (roles.get(u) == Role.SEEKER) win.add(u);
            session.end(win, "<red><bold>Ищущие нашли всех!");
        } else if (count(Role.SEEKER) == 0) {
            session.end(hiders(), "<green><bold>Ищущих не осталось — победа прячущихся!");
        }
    }

    private Set<UUID> hiders() {
        Set<UUID> out = new HashSet<>();
        for (UUID u : session.aliveIds()) if (roles.get(u) == Role.HIDER) out.add(u);
        return out;
    }

    @Override
    public void onTimeUp() {
        session.end(hiders(), "<green><bold>Время вышло — прячущиеся победили!");
    }

    @Override
    public void onBlockBreak(BlockBreakEvent e) { e.setCancelled(true); }

    @Override
    public void onBlockPlace(BlockPlaceEvent e) { e.setCancelled(true); }

    // ------------------------------------------------------------------ предметы

    @Override
    public void onHotbar(Player p, String payload) {
        switch (payload) {
            case "disguise" -> {
                if (!prop || !isHider(p)) return;
                Block b = p.getTargetBlockExact(6);
                if (b == null || !allowed(b.getType())) {
                    p.sendActionBar(Msg.parse("<red>Посмотри на подходящий блок (до 6 блоков)"));
                    return;
                }
                disguise(p, b.getBlockData());
                p.playSound(p.getLocation(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1f, 1.2f);
                p.sendActionBar(Msg.parse("<aqua>Теперь ты: <white>" + pretty(b.getType())));
            }
            case "taunt" -> { if (isHider(p)) openMenu(p, taunts, true); }
            case "provoke" -> { if (isSeeker(p)) openMenu(p, provocations, false); }
            default -> {
                if (!payload.startsWith("provoke:") || !isSeeker(p)) return;
                String id = payload.substring(8);
                for (Effect ef : provocations) if (ef.id().equals(id)) { provoke(p, ef); return; }
            }
        }
    }

    private void openMenu(Player p, List<Effect> list, boolean taunt) {
        Menu m = new Menu(Math.max(1, (list.size() + 8) / 9), taunt ? "<dark_gray>Таунты" : "<dark_gray>Провокации") {
            {
                int slot = 0;
                for (Effect ef : list) {
                    long left = taunt ? tauntLeft(p, ef) : cooldownLeft(p, ef);
                    List<String> lore;
                    if (taunt) {
                        lore = new ArrayList<>(ef.lore());
                        lore.add("");
                        lore.add("<light_purple>+" + ef.points() + " очк." + (tauntHeal > 0 ? " <red>+" + hp(tauntHeal) + "❤" : ""));
                        lore.add("<gray>Перезарядка: " + Math.max(ef.cooldown(), tauntCooldown) + " сек." + (tauntCooldown > 0 ? " (общая для всех таунтов)" : ""));
                        lore.add(left > 0 ? "<red>Ещё " + left + " сек." : "<green>▶ Нажми");
                    } else {
                        lore = provokeLore(ef, left);
                    }
                    set(slot++, Items.icon(left > 0 ? Material.GRAY_DYE : ef.icon(), ef.display(), lore, false), pl -> {
                        pl.closeInventory();
                        if (taunt) taunt(pl, ef); else provoke(pl, ef);
                    });
                }
            }
        };
        m.open(p);
    }

    private long cooldownLeft(Player p, Effect ef) {
        Long until = cooldowns.get(p.getUniqueId() + ":" + ef.id());
        return until == null ? 0 : Math.max(0, (until - System.currentTimeMillis() + 999) / 1000);
    }

    private boolean useCooldown(Player p, Effect ef) {
        long left = cooldownLeft(p, ef);
        if (left > 0) { p.sendActionBar(Msg.parse("<red>Перезарядка: ещё " + left + " сек.")); return false; }
        cooldowns.put(p.getUniqueId() + ":" + ef.id(), System.currentTimeMillis() + ef.cooldown() * 1000L);
        return true;
    }

    private static final String TAUNT_SHARED = "#taunt";

    /** Сколько ждать до таунта: его собственная перезарядка или общая (taunt-cooldown), что дольше. */
    private long tauntLeft(Player p, Effect ef) {
        Long shared = cooldowns.get(p.getUniqueId() + ":" + TAUNT_SHARED);
        long s = shared == null ? 0 : Math.max(0, (shared - System.currentTimeMillis() + 999) / 1000);
        return Math.max(s, cooldownLeft(p, ef));
    }

    private void taunt(Player p, Effect ef) {
        if (!session.isPlaying() || !isHider(p)) return;
        long left = tauntLeft(p, ef);
        if (left > 0) { p.sendActionBar(Msg.parse("<red>Перезарядка: ещё " + left + " сек.")); return; }
        long now = System.currentTimeMillis();
        cooldowns.put(p.getUniqueId() + ":" + ef.id(), now + ef.cooldown() * 1000L);
        if (tauntCooldown > 0) cooldowns.put(p.getUniqueId() + ":" + TAUNT_SHARED, now + tauntCooldown * 1000L);
        emit(p, ef);
        points.merge(p.getUniqueId(), ef.points(), Integer::sum);
        String healed = "";
        if (tauntHeal > 0) {
            double before = p.getHealth(), after = Math.min(maxHealth(p), before + tauntHeal);
            if (after > before) {
                p.setHealth(after); // напрямую, мимо no-regen
                healed = " <red>+" + hp(after - before) + "❤";
            }
        }
        p.sendActionBar(Msg.parse("<light_purple>Таунт «" + ef.display() + "<light_purple>»: +" + ef.points() + " очк." + healed));
    }

    /** Звук, частицы или фейерверк из места, где прячется игрок (слышно и видно всем). */
    private void emit(Player hider, Effect ef) {
        Location at = prop && displays.containsKey(hider.getUniqueId())
                ? displays.get(hider.getUniqueId()).getLocation().add(0.5, 0.6, 0.5) : hider.getLocation().add(0, 1, 0);
        World w = at.getWorld();
        if (ef.sound() != null && !ef.sound().isBlank()) {
            try {
                w.playSound(net.kyori.adventure.sound.Sound.sound(Key.key(ef.sound()), net.kyori.adventure.sound.Sound.Source.PLAYER, ef.volume(), ef.pitch()),
                        at.getX(), at.getY(), at.getZ());
            } catch (Exception ex) {
                session.plugin().getLogger().warning("Таунт " + ef.id() + ": неверный звук " + ef.sound());
            }
        }
        if (ef.particle() != null && !ef.particle().isBlank()) {
            try {
                Particle pt = Particle.valueOf(ef.particle().toUpperCase(Locale.ROOT));
                if (pt.getDataType() == Void.class) w.spawnParticle(pt, at, 20, 0.4, 0.5, 0.4, 0.02);
            } catch (Exception ex) {
                session.plugin().getLogger().warning("Таунт " + ef.id() + ": неверные частицы " + ef.particle());
            }
        }
        if ("firework".equals(ef.type())) firework(at);
    }

    private void firework(Location at) {
        at.getWorld().spawn(at, Firework.class, fw -> {
            FireworkMeta fm = fw.getFireworkMeta();
            fm.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL).withColor(Color.FUCHSIA, Color.AQUA).build());
            fm.setPower(1);
            fw.setFireworkMeta(fm);
        });
    }

    /**
     * Провокация ищущего: прячущиеся (все или ближайший — target) выдают себя (type: sound | glow | firework | particles).
     * Возвращает причину, по которой сейчас нельзя, или null.
     */
    private String provokeBlocked(Effect ef) {
        if (hideLeft > 0) return "Подожди, пока все спрячутся";
        int hiders = count(Role.HIDER);
        if (ef.maxHiders() > 0 && hiders > ef.maxHiders())
            return "Можно, когда прячущихся " + ef.maxHiders() + " или меньше (сейчас " + hiders + ")";
        int timeLeft = session.mode.timeLimit > 0 ? session.mode.timeLimit - session.elapsed() : -1;
        if (ef.maxTimeLeft() > 0 && timeLeft > ef.maxTimeLeft())
            return "Можно за " + Msg.time(ef.maxTimeLeft()) + " до конца (ещё " + Msg.time(timeLeft - ef.maxTimeLeft()) + ")";
        return null;
    }

    private void provoke(Player seeker, Effect ef) {
        if (!session.isPlaying() || !isSeeker(seeker)) return;
        String blocked = provokeBlocked(ef);
        if (blocked != null) { seeker.sendActionBar(Msg.parse("<red>" + blocked)); return; }
        List<Player> hs = new ArrayList<>();
        for (Player p : session.alivePlayers()) if (isHider(p)) hs.add(p);
        if ("nearest".equals(ef.target())) {
            Player best = null;
            double bd = Double.MAX_VALUE;
            for (Player h : hs) {
                if (!h.getWorld().equals(seeker.getWorld())) continue;
                double d = h.getLocation().distanceSquared(seeker.getLocation());
                if (d < bd) { bd = d; best = h; }
            }
            hs = best == null ? List.of() : List.of(best);
        }
        if (hs.isEmpty()) { seeker.sendActionBar(Msg.parse("<red>Рядом никого нет")); return; }
        if (!useCooldown(seeker, ef)) return;
        seeker.setCooldown(ef.icon(), ef.cooldown() * 20); // полоска перезарядки на предмете
        final List<Player> targets = hs;
        switch (ef.type()) {
            case "glow" -> { for (Player h : targets) glow(h, ef.duration()); }
            case "particles" -> {
                int[] left = {ef.duration()};
                Bukkit.getScheduler().runTaskTimer(session.plugin(), t -> {
                    if (left[0]-- <= 0 || !session.isPlaying()) { t.cancel(); return; }
                    for (Player h : targets) if (isHider(h)) emit(h, new Effect(ef.id(), "", Material.AIR, null, 1, 1, ef.particle() != null ? ef.particle() : "HAPPY_VILLAGER", 0, 0, "particles", 0, List.of(), "all", 0, 0));
                }, 0L, 20L);
            }
            default -> { for (Player h : targets) emit(h, ef); }
        }
        for (Player h : targets) h.sendActionBar(Msg.parse("<red>Ищущий использовал «" + ef.display() + "<red>» — ты выдал себя!"));
        session.broadcast("<gold>" + Msg.esc(seeker.getName()) + " <gray>использовал провокацию «" + ef.display() + "<gray>»");
    }

    /** Подсветка сквозь стены на sec секунд: игроку — эффект «Свечение», в prop hunt — его блоку. */
    private void glow(Player h, int sec) {
        BlockDisplay d = displays.get(h.getUniqueId());
        if (d != null && d.isValid()) {
            d.setGlowing(true);
            Bukkit.getScheduler().runTaskLater(session.plugin(), () -> { if (d.isValid()) d.setGlowing(false); }, sec * 20L);
        } else {
            h.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, sec * 20, 0, false, false));
        }
    }

    /** Автоматический звук (auto-taunt) — случайный таунт без очков и без перезарядки. */
    private void forcedNoise(Player hider, Effect ef) {
        if (ef == null) {
            if (taunts.isEmpty()) return;
            ef = taunts.get(session.random().nextInt(taunts.size()));
        }
        emit(hider, ef);
    }

    // ------------------------------------------------------------------ инфо

    @Override
    public void placeholders(Player p, Map<String, String> ph) {
        Role r = roles.get(p.getUniqueId());
        ph.put("role", r == Role.SEEKER ? "<red>Ищущий" : r == Role.HIDER ? "<green>Прячущийся" : "<gray>Наблюдатель");
        ph.put("hiders", String.valueOf(count(Role.HIDER)));
        ph.put("seekers", String.valueOf(count(Role.SEEKER)));
        ph.put("points", String.valueOf(points.getOrDefault(p.getUniqueId(), 0)));
        ph.put("hide_left", hideLeft > 0 ? Msg.time(hideLeft) : "<red>ищут");
        BlockData d = disguise.get(p.getUniqueId());
        ph.put("disguise", d == null ? "-" : pretty(d.getMaterial()));
    }

    @Override
    public void onEnd() {
        for (UUID u : new ArrayList<>(displays.keySet())) undisguise(u);
        session.plugin().visibility().refreshAll(session);
        // лучшие по таунтам
        List<Map.Entry<UUID, Integer>> top = new ArrayList<>(points.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue());
        if (!top.isEmpty() && top.get(0).getValue() > 0) {
            StringBuilder sb = new StringBuilder("<light_purple>Таунты: ");
            for (int i = 0; i < Math.min(3, top.size()); i++) {
                Player p = Bukkit.getPlayer(top.get(i).getKey());
                if (i > 0) sb.append("<gray>, ");
                sb.append("<white>").append(p != null ? Msg.esc(p.getName()) : "?").append(" <light_purple>").append(top.get(i).getValue());
            }
            session.broadcast(sb.toString());
        }
    }

    private static String pretty(Material m) {
        String s = m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
