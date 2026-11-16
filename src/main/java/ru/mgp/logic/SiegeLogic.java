package ru.mgp.logic;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import ru.mgp.party.Party;
import ru.mgp.session.ActionRunner;
import ru.mgp.session.GameSession;
import ru.mgp.util.*;

import java.util.*;

/**
 * Осада (Siege): защитники держат крепость и чинят её, атакующие (больше ресурсов, способности) должны убить
 * всех защитников. Крепость — области fortress в файле карты.
 *   • Руками ломаются только блоки из breakable-blocks (земля, доски...) и блоки, поставленные за игру.
 *   • ТНТ поджигается сразу при установке и ломает всё, кроме explosion-proof. Своих ТНТ не ранит.
 *   • Молот защитников (ПКМ по стене) возвращает исходные блоки крепости рядом.
 *   • Защитник умирает один раз, атакующий возрождается через attacker-respawn сек.
 *   • Атакующие победили — все защитники мертвы; защитники — продержались до конца time-limit.
 */
public final class SiegeLogic extends GameLogic {
    public enum Team {
        DEFENDERS("defenders", "<aqua>", "Защитники", NamedTextColorHolder.AQUA),
        ATTACKERS("attackers", "<red>", "Атакующие", NamedTextColorHolder.RED);
        final String id, color, title;
        final net.kyori.adventure.text.format.NamedTextColor named;
        Team(String id, String color, String title, net.kyori.adventure.text.format.NamedTextColor named) {
            this.id = id; this.color = color; this.title = title; this.named = named;
        }
    }

    /** Цвета ников (enum не может ссылаться на статические поля другого класса в своём конструкторе напрямую — так проще). */
    private static final class NamedTextColorHolder {
        static final net.kyori.adventure.text.format.NamedTextColor AQUA = net.kyori.adventure.text.format.NamedTextColor.AQUA;
        static final net.kyori.adventure.text.format.NamedTextColor RED = net.kyori.adventure.text.format.NamedTextColor.RED;
    }

    private record Ability(String id, Material item, String name, List<String> lore, int cooldown, Team team, List<String> actions) {}

    private static final String HAMMER = "siege_hammer";
    private static final String ABILITY = "siege_ability:";
    private static final int MAX_FORTRESS = 600_000;

    // настройки
    private final double defenderRatio;
    private final Set<Material> breakable, explosionProof;
    private final boolean autoTnt;
    private final int tntFuse;
    private final int attackerRespawn;
    private final boolean attackerKeepInventory;
    private final int repairRadius, repairPerUse;
    private final double repairCooldown;
    private final Material hammerItem;
    private final List<ItemSpec> defenderItems = new ArrayList<>(), attackerItems = new ArrayList<>();
    private final List<Ability> abilities = new ArrayList<>();

    // состояние
    private final Map<UUID, Team> teams = new HashMap<>();
    private final List<Region> fortress = new ArrayList<>();
    /** Исходные блоки крепости: ключ позиции → данные блока. */
    private final Map<Long, BlockData> original = new HashMap<>();
    /** Блоки, поставленные игроками за игру (их можно ломать). */
    private final Set<Long> placed = new HashSet<>();
    private final Map<UUID, Long> repairAt = new HashMap<>();
    private final Map<String, Long> abilityAt = new HashMap<>();
    /** Атакующий в ожидании возрождения: когда вернуть (elapsed). */
    private final Map<UUID, Integer> respawnAt = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private int integrityNow = 100;

    public SiegeLogic(GameSession s) {
        super(s);
        ConfigurationSection st = s.mode.settings;
        defenderRatio = Math.max(0.05, Math.min(0.95, st.getDouble("defender-ratio", 0.4)));
        List<String> unknown = new ArrayList<>();
        breakable = Cfg.materials(st.getStringList("breakable-blocks"), unknown);
        explosionProof = Cfg.materials(st.isList("explosion-proof") ? st.getStringList("explosion-proof") : List.of("BEDROCK", "BARRIER"), unknown);
        if (!unknown.isEmpty()) s.plugin().getLogger().warning("Осада " + s.mode.id + ": не распознаны блоки " + unknown);
        autoTnt = st.getBoolean("tnt-auto-ignite", true);
        tntFuse = Math.max(1, (int) Math.round(st.getDouble("tnt-fuse", 3) * 20));
        attackerRespawn = st.getInt("attacker-respawn", 5);
        attackerKeepInventory = st.getBoolean("attacker-keep-inventory", true);
        ConfigurationSection rep = st.getConfigurationSection("repair");
        repairRadius = rep == null ? 3 : Math.max(1, rep.getInt("radius", 3));
        repairPerUse = rep == null ? 3 : Math.max(1, rep.getInt("blocks-per-use", 3));
        repairCooldown = rep == null ? 1.0 : Math.max(0.1, rep.getDouble("cooldown", 1.0));
        hammerItem = Cfg.material(rep == null ? null : rep.getString("item"), Material.BRUSH);
        for (Object o : st.getList("defender-items", List.of())) defenderItems.add(ItemSpec.parse(o));
        for (Object o : st.getList("attacker-items", List.of())) attackerItems.add(ItemSpec.parse(o));
        for (Map<?, ?> raw : st.getMapList("abilities")) {
            Map<String, Object> m = Cfg.asMap(raw);
            String id = Cfg.str(m, "id", "ability" + abilities.size());
            Team t = "defenders".equalsIgnoreCase(Cfg.str(m, "team", "attackers")) ? Team.DEFENDERS : Team.ATTACKERS;
            abilities.add(new Ability(id, Cfg.material(Cfg.str(m, "item", null), Material.FEATHER), Cfg.str(m, "name", "<yellow>" + id),
                    Cfg.strings(m.get("lore")), Math.max(0, Cfg.integer(m, "cooldown", 10)), t, Cfg.strings(m.get("actions"))));
        }
    }

    // ------------------------------------------------------------------ команды

    public Team team(Player p) { return teams.get(p.getUniqueId()); }

    @Override
    public String zoneTeam(Player p) {
        Team t = teams.get(p.getUniqueId());
        return t == null ? null : t == Team.DEFENDERS ? "defenders" : "attackers";
    }

    private boolean is(Player p, Team t) { return teams.get(p.getUniqueId()) == t; }

    private List<Player> alive(Team t) {
        List<Player> out = new ArrayList<>();
        for (Player p : session.alivePlayers()) if (is(p, t)) out.add(p);
        return out;
    }

    /** Делим по командам, пати остаются вместе. */
    private void assignTeams(List<Player> all) {
        Map<Object, List<Player>> groups = new LinkedHashMap<>();
        for (Player p : all) {
            Party party = session.plugin().parties().of(p);
            groups.computeIfAbsent(party != null ? party : p.getUniqueId(), k -> new ArrayList<>()).add(p);
        }
        List<List<Player>> gl = new ArrayList<>(groups.values());
        Collections.shuffle(gl, session.random());
        gl.sort(Comparator.comparingInt((List<Player> g) -> -g.size()));
        int want = Math.max(1, Math.min(all.size() - 1, (int) Math.round(all.size() * defenderRatio)));
        int def = 0;
        for (List<Player> g : gl) {
            boolean toDef = def < want && (def + g.size() <= want || def == 0);
            for (Player p : g) teams.put(p.getUniqueId(), toDef ? Team.DEFENDERS : Team.ATTACKERS);
            if (toDef) def += g.size();
        }
        // все в одной команде (одна большая пати) — делим поровну, иначе играть некому
        if (def == 0 || def == all.size()) {
            for (int i = 0; i < all.size(); i++) teams.put(all.get(i).getUniqueId(), i < want ? Team.DEFENDERS : Team.ATTACKERS);
        }
    }

    // ------------------------------------------------------------------ старт

    @Override
    public void onStart() {
        List<Player> all = session.alivePlayers();
        if (all.size() < 2) { session.end(List.of(), "<red>Для осады нужно хотя бы 2 игрока."); return; }
        assignTeams(all);
        for (Player p : all) PlayerUtil.reset(p, GameMode.SURVIVAL);
        session.spreadToSpawns(alive(Team.DEFENDERS), Team.DEFENDERS.id);
        session.spreadToSpawns(alive(Team.ATTACKERS), Team.ATTACKERS.id);
        loadFortress();
        for (Player p : all) {
            Team t = team(p);
            equip(p);
            Msg.title(p, t.color + "<bold>" + t.title.toUpperCase(Locale.ROOT),
                    t == Team.DEFENDERS ? "<gray>Удержи крепость до конца! Молот — чинить стены" : "<gray>Убей всех защитников! ТНТ ломает стены");
            p.sendMessage(Msg.parse(t.color + "Ты в команде «" + t.title + "». "
                    + (t == Team.DEFENDERS ? "<gray>Продержитесь " + timeText(session.mode.timeLimit) + ". ПКМ молотом по стене — починка."
                    : "<gray>Ломать руками можно только землю, доски и т.п. Стены пробивай ТНТ — его мало!")));
        }
        session.plugin().visibility().refreshAll(session);
    }

    private static String timeText(int sec) {
        return sec <= 0 ? "сколько сможете" : (sec / 60) + ":" + String.format(Locale.ROOT, "%02d", sec % 60);
    }

    private void equip(Player p) {
        Team t = team(p);
        // кит команды (kits с team: defenders / attackers) заменяет defender-items / attacker-items
        ru.mgp.config.Kit kit = session.equip(p, t == Team.DEFENDERS ? "defenders" : "attackers");
        if (kit == null || kit.team() == null) for (ItemSpec s : t == Team.DEFENDERS ? defenderItems : attackerItems) {
            ItemStack it = s.build();
            if (s.slot != null) applySlot(p, s.slot, it); else p.getInventory().addItem(it);
        }
        if (t == Team.DEFENDERS) p.getInventory().addItem(Items.tagged(hammerItem, "<aqua><bold>Молот</bold> <gray>(ПКМ по стене)", "logic:" + HAMMER,
                "<gray>Восстанавливает разрушенные", "<gray>блоки крепости рядом"));
        for (Ability a : abilities) if (a.team() == t) {
            List<String> lore = new ArrayList<>(a.lore());
            lore.add("<dark_gray>ПКМ, перезарядка " + a.cooldown() + " с");
            p.getInventory().addItem(Items.tagged(a.item(), a.name(), "logic:" + ABILITY + a.id(), lore.toArray(String[]::new)));
        }
        // цветная кожаная броня, если брони нет: видно, кто свой
        ItemStack chest = p.getInventory().getChestplate();
        if (chest == null || chest.getType().isAir()) {
            ItemStack c = new ItemStack(Material.LEATHER_CHESTPLATE);
            if (c.getItemMeta() instanceof org.bukkit.inventory.meta.LeatherArmorMeta lm) {
                lm.setColor(t == Team.DEFENDERS ? Color.fromRGB(0x3c8dde) : Color.fromRGB(0xd23c3c));
                lm.setUnbreakable(true);
                c.setItemMeta(lm);
            }
            p.getInventory().setChestplate(c);
        }
    }

    private static void applySlot(Player p, String slot, ItemStack it) {
        var inv = p.getInventory();
        switch (slot.toLowerCase(Locale.ROOT)) {
            case "head", "helmet" -> inv.setHelmet(it);
            case "chest", "chestplate" -> inv.setChestplate(it);
            case "legs", "leggings" -> inv.setLeggings(it);
            case "feet", "boots" -> inv.setBoots(it);
            case "offhand" -> inv.setItemInOffHand(it);
            default -> {
                try { inv.setItem(Integer.parseInt(slot), it); } catch (NumberFormatException e) { inv.addItem(it); }
            }
        }
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private void loadFortress() {
        fortress.clear();
        original.clear();
        World w = session.gameWorld();
        for (Map<?, ?> raw : session.map().yaml.getMapList("fortress")) {
            Map<String, Object> m = Cfg.asMap(raw);
            Pos a = Pos.parse(Cfg.str(m, "min", null)), b = Pos.parse(Cfg.str(m, "max", null));
            if (a != null && b != null) fortress.add(Region.of(a, b));
        }
        if (fortress.isEmpty()) {
            session.plugin().getLogger().warning("Карта " + session.map().id + ": нет крепости (fortress). /mgp setup fortress <карта> из выделения");
            return;
        }
        for (Region r : fortress) {
            long vol = (long) (r.maxX() - r.minX() + 1) * (r.maxY() - r.minY() + 1) * (r.maxZ() - r.minZ() + 1);
            if (original.size() + vol > MAX_FORTRESS) {
                session.plugin().getLogger().warning("Крепость карты " + session.map().id + " слишком большая, часть не учитывается");
                break;
            }
            for (int x = r.minX(); x <= r.maxX(); x++)
                for (int y = r.minY(); y <= r.maxY(); y++)
                    for (int z = r.minZ(); z <= r.maxZ(); z++) {
                        Block b = w.getBlockAt(x, y, z);
                        if (!b.getType().isAir() && !b.isLiquid()) original.put(key(x, y, z), b.getBlockData());
                    }
        }
    }

    private boolean inFortress(Block b) {
        for (Region r : fortress) if (r.contains(b.getX(), b.getY(), b.getZ())) return true;
        return false;
    }

    /** Целостность крепости: доля исходных блоков на месте. */
    private int integrity() {
        if (original.isEmpty()) return 100;
        World w = session.gameWorld();
        int ok = 0;
        for (Map.Entry<Long, BlockData> e : original.entrySet()) {
            long k = e.getKey();
            int x = (int) (k >> 38), z = (int) ((k >> 12) & 0x3FFFFFF), y = (int) (k & 0xFFF);
            if (x >= 1 << 25) x -= 1 << 26;
            if (z >= 1 << 25) z -= 1 << 26;
            if (y >= 1 << 11) y -= 1 << 12;
            if (w.getBlockAt(x, y, z).getType() == e.getValue().getMaterial()) ok++;
        }
        return (int) Math.floor(ok * 100.0 / original.size());
    }

    // ------------------------------------------------------------------ блоки

    @Override
    public void onBlockBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        long k = key(b.getX(), b.getY(), b.getZ());
        if (placed.remove(k)) return;                       // поставлен за игру — можно
        if (breakable.contains(b.getType())) return;         // земля, доски...
        e.setCancelled(true);
        e.getPlayer().sendActionBar(Msg.parse(inFortress(b) ? "<red>Стену крепости руками не сломать — нужен ТНТ" : "<red>Этот блок не ломается"));
    }

    @Override
    public void onBlockPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlockPlaced();
        if (autoTnt && b.getType() == Material.TNT) {
            e.setCancelled(true);
            ItemStack hand = e.getItemInHand();
            hand.setAmount(hand.getAmount() - 1);
            p.getInventory().setItem(e.getHand(), hand.getAmount() <= 0 ? null : hand);
            Location at = b.getLocation().add(0.5, 0, 0.5);
            b.getWorld().spawn(at, TNTPrimed.class, t -> { t.setFuseTicks(tntFuse); t.setSource(p); });
            b.getWorld().playSound(at, Sound.ENTITY_TNT_PRIMED, 1f, 1f);
            return;
        }
        placed.add(key(b.getX(), b.getY(), b.getZ()));
    }

    @Override
    public void onBucket(PlayerBucketEmptyEvent e) {
        e.setCancelled(true); // вода и лава ломали бы осаду
    }

    @Override
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> explosionProof.contains(b.getType()));
        for (Block b : e.blockList()) placed.remove(key(b.getX(), b.getY(), b.getZ()));
        e.setYield(0f); // без выпадения блоков: ресурсы не копятся
    }

    // ------------------------------------------------------------------ урон и смерть

    private static Player attacker(EntityDamageEvent e) {
        if (!(e instanceof EntityDamageByEntityEvent ee)) return null;
        Entity d = ee.getDamager();
        if (d instanceof Player p) return p;
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        if (d instanceof TNTPrimed t && t.getSource() instanceof Player p) return p;
        return null;
    }

    @Override
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        if (respawnAt.containsKey(victim.getUniqueId())) { e.setCancelled(true); return; }
        Player a = attacker(e);
        if (a != null && !a.equals(victim) && team(a) == team(victim)) e.setCancelled(true); // свои
        if (a != null && a.equals(victim) && e.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) e.setDamage(e.getDamage() * 0.3);
    }

    @Override
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        e.deathMessage(null);
        Player killer = p.getKiller();
        Team t = team(p);
        if (killer != null && !killer.equals(p)) {
            session.addKill(killer.getUniqueId());
            killer.playSound(killer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        }
        String who = (t == null ? "<white>" : t.color) + Msg.esc(p.getName());
        Team kt = killer == null ? null : team(killer);
        session.broadcast(who + " <gray>" + (killer != null && !killer.equals(p) ? "убит игроком " + (kt == null ? "<white>" : kt.color) + Msg.esc(killer.getName()) : "погиб"));
        if (t == Team.ATTACKERS && attackerRespawn >= 0) {
            if (attackerKeepInventory) { e.setKeepInventory(true); e.getDrops().clear(); e.setKeepLevel(true); e.setDroppedExp(0); }
            respawnAt.put(p.getUniqueId(), session.elapsed() + attackerRespawn);
            return;
        }
        // защитник (или атакующий без возрождений) выбывает; служебные предметы не выпадают
        e.getDrops().removeIf(it -> Keys.tag(it) != null);
        session.eliminate(p);
        Msg.title(p, "<red><bold>ТЫ ВЫБЫЛ", "<gray>Теперь ты наблюдатель");
        checkWin();
    }

    @Override
    public Location respawnLocation(Player p) {
        if (!respawnAt.containsKey(p.getUniqueId())) return null;
        Location l = session.spawnFor(Team.ATTACKERS.id);
        Bukkit.getScheduler().runTask(session.plugin(), () -> {
            if (p.isOnline() && respawnAt.containsKey(p.getUniqueId())) p.setGameMode(GameMode.SPECTATOR);
        });
        return l;
    }

    private void respawnNow(Player p) {
        respawnAt.remove(p.getUniqueId());
        p.teleport(session.spawnFor(Team.ATTACKERS.id));
        p.setGameMode(GameMode.SURVIVAL);
        p.setHealth(Objects.requireNonNull(p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)).getValue());
        p.setFoodLevel(20);
        p.setFireTicks(0);
        if (!attackerKeepInventory) equip(p);
        Msg.title(p, "<red><bold>В БОЙ!", "");
    }

    @Override
    public void onLeave(Player p, boolean wasAlive) {
        respawnAt.remove(p.getUniqueId());
        BossBar bar = bars.remove(p.getUniqueId());
        if (bar != null) p.hideBossBar(bar);
        if (wasAlive) {
            session.broadcast("<red>" + Msg.esc(p.getName()) + " <gray>покинул игру.");
            checkWin();
        }
    }

    private void checkWin() {
        if (!session.isPlaying()) return;
        if (alive(Team.DEFENDERS).isEmpty()) win(Team.ATTACKERS, "<red>Атакующие взяли крепость!");
        else if (alive(Team.ATTACKERS).isEmpty()) win(Team.DEFENDERS, "<aqua>Атакующих не осталось — крепость устояла!");
    }

    private void win(Team t, String reason) {
        List<UUID> w = new ArrayList<>();
        for (Map.Entry<UUID, Team> e : teams.entrySet()) if (e.getValue() == t && Bukkit.getPlayer(e.getKey()) != null) w.add(e.getKey());
        session.end(w, reason);
    }

    @Override
    public void onTimeUp() { win(Team.DEFENDERS, "<aqua>Защитники продержались — крепость устояла!"); }

    // ------------------------------------------------------------------ молот и способности

    @Override
    public void onHotbar(Player p, String payload) {
        if (respawnAt.containsKey(p.getUniqueId())) return;
        if (payload.equals(HAMMER)) { repair(p); return; }
        if (payload.startsWith(ABILITY)) {
            String id = payload.substring(ABILITY.length());
            for (Ability a : abilities) if (a.id().equals(id)) { use(p, a); return; }
        }
    }

    private void use(Player p, Ability a) {
        if (team(p) != a.team()) return;
        String k = p.getUniqueId() + ":" + a.id();
        long now = System.currentTimeMillis();
        Long until = abilityAt.get(k);
        if (until != null && until > now) {
            p.sendActionBar(Msg.parse("<red>Перезарядка: " + ((until - now + 999) / 1000) + " с"));
            return;
        }
        abilityAt.put(k, now + a.cooldown() * 1000L);
        p.setCooldown(a.item(), a.cooldown() * 20);
        ActionRunner.run(session, a.actions(), p);
    }

    private void repair(Player p) {
        if (team(p) != Team.DEFENDERS) return;
        long now = System.currentTimeMillis();
        Long at = repairAt.get(p.getUniqueId());
        if (at != null && at > now) return;
        Block target = p.getTargetBlockExact(6);
        Location c = target != null ? target.getLocation().add(0.5, 0.5, 0.5) : p.getEyeLocation().add(p.getLocation().getDirection().multiply(3));
        World w = p.getWorld();
        int r = repairRadius;
        List<int[]> missing = new ArrayList<>();
        for (int x = -r; x <= r; x++)
            for (int y = -r; y <= r; y++)
                for (int z = -r; z <= r; z++) {
                    if (x * x + y * y + z * z > r * r) continue;
                    int bx = c.getBlockX() + x, by = c.getBlockY() + y, bz = c.getBlockZ() + z;
                    BlockData orig = original.get(key(bx, by, bz));
                    if (orig == null) continue;
                    Block b = w.getBlockAt(bx, by, bz);
                    if (b.getType() == orig.getMaterial()) continue;
                    if (!b.getType().isAir() && !b.isLiquid() && b.getType() != Material.FIRE) continue; // занято чужим блоком
                    missing.add(new int[]{bx, by, bz, x * x + y * y + z * z});
                }
        if (missing.isEmpty()) {
            p.sendActionBar(Msg.parse(original.isEmpty() ? "<gray>На карте не задана крепость" : "<gray>Здесь нечего чинить"));
            repairAt.put(p.getUniqueId(), now + 300);
            return;
        }
        missing.sort(Comparator.comparingInt(a -> a[3]));
        int done = 0;
        for (int[] m : missing) {
            if (done >= repairPerUse) break;
            Block b = w.getBlockAt(m[0], m[1], m[2]);
            BoundingBox box = BoundingBox.of(b);
            if (!w.getNearbyEntities(box, en -> en instanceof LivingEntity).isEmpty()) continue; // не замуровывать игроков
            b.setBlockData(original.get(key(m[0], m[1], m[2])), false);
            placed.remove(key(m[0], m[1], m[2]));
            w.spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(0.5, 0.5, 0.5), 6, 0.3, 0.3, 0.3, 0);
            done++;
        }
        repairAt.put(p.getUniqueId(), now + Math.round(repairCooldown * 1000));
        if (done > 0) {
            w.playSound(c, Sound.BLOCK_ANVIL_USE, 0.5f, 1.6f);
            p.sendActionBar(Msg.parse("<green>Починено блоков: " + done + " <gray>(осталось рядом " + (missing.size() - done) + ")"));
        } else p.sendActionBar(Msg.parse("<yellow>Отойди: в пролом не поставить блок, там кто-то стоит"));
    }

    @Override
    public void onInteract(PlayerInteractEvent e) {
        // ТНТ можно поджигать только у атакующих: защитники не взрывают свою крепость
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getHand() == EquipmentSlot.HAND && e.getItem() != null
                && e.getItem().getType() == Material.TNT && is(e.getPlayer(), Team.DEFENDERS)) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(Msg.parse("<red>Защитникам ТНТ не положен"));
        }
    }

    // ------------------------------------------------------------------ тик, панель, цвета

    @Override
    public void onTick() {
        int now = session.elapsed();
        for (Map.Entry<UUID, Integer> e : new ArrayList<>(respawnAt.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) { respawnAt.remove(e.getKey()); continue; }
            int left = e.getValue() - now;
            if (left <= 0) respawnNow(p);
            else p.sendActionBar(Msg.parse("<gray>Возрождение через <yellow>" + left + " <gray>с"));
        }
        if (!original.isEmpty()) {
            integrityNow = integrity();
            float prog = Math.max(0, Math.min(1, integrityNow / 100f));
            String title = "<aqua>Крепость: <white>" + integrityNow + "%";
            for (Player p : session.online()) {
                BossBar bar = bars.get(p.getUniqueId());
                if (bar == null) {
                    bar = BossBar.bossBar(Msg.parse(title), prog, BossBar.Color.BLUE, BossBar.Overlay.NOTCHED_10);
                    bars.put(p.getUniqueId(), bar);
                    p.showBossBar(bar);
                } else { bar.name(Msg.parse(title)); bar.progress(prog); }
            }
        }
    }

    @Override
    public boolean handleAction(String action, Player target) {
        // mode: defenders-win | attackers-win — например, из цели на разрушение
        switch (action.trim().toLowerCase(Locale.ROOT)) {
            case "defenders-win" -> { win(Team.DEFENDERS, "<aqua>Защитники победили!"); return true; }
            case "attackers-win" -> { win(Team.ATTACKERS, "<red>Атакующие победили!"); return true; }
            default -> { return false; }
        }
    }

    @Override
    public void placeholders(Player p, Map<String, String> ph) {
        Team t = team(p);
        ph.put("team", t == null ? "<gray>-" : t.color + t.title);
        ph.put("defenders", String.valueOf(alive(Team.DEFENDERS).size()));
        ph.put("attackers", String.valueOf(alive(Team.ATTACKERS).size()));
        ph.put("integrity", original.isEmpty() ? "-" : integrityNow + "%");
        Integer r = respawnAt.get(p.getUniqueId());
        ph.put("respawn", r == null ? "-" : Math.max(0, r - session.elapsed()) + " с");
    }

    @Override
    public Map<String, net.kyori.adventure.text.format.NamedTextColor> teamColors() {
        Map<String, net.kyori.adventure.text.format.NamedTextColor> m = new HashMap<>();
        for (Map.Entry<UUID, Team> e : teams.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) m.put(p.getName(), e.getValue().named);
        }
        return m;
    }

    @Override
    public void onEnd() {
        for (UUID u : new ArrayList<>(bars.keySet())) {
            Player p = Bukkit.getPlayer(u);
            BossBar bar = bars.remove(u);
            if (p != null && bar != null) p.hideBossBar(bar);
        }
        for (UUID u : respawnAt.keySet()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.setGameMode(GameMode.ADVENTURE);
        }
        respawnAt.clear();
    }
}
