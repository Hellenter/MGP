package ru.mgp.session;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import ru.mgp.config.MapConfig;
import ru.mgp.util.Msg;
import ru.mgp.util.Region;

import java.util.*;

/**
 * Play-зона карты (maps/<карта>.yml → play-zone) и стартовые зоны команд (team-zones).
 * Граница — барьер: пройти за неё нельзя (запас settings.zone-margin блоков, по умолчанию 0), у края видна
 * сетка частиц (только этому игроку). Никого никуда не телепортирует: кто оказался снаружи (отбросило взрывом),
 * может идти только обратно к зоне.
 * Зоны команд снимаются через team-zones-open секунд после старта или действием zones: open.
 */
public final class PlayZone {
    private static final Particle.DustOptions EDGE = new Particle.DustOptions(Color.fromRGB(0x7fd4ff), 0.9f);
    private static final Particle.DustOptions TEAM_EDGE = new Particle.DustOptions(Color.fromRGB(0xffa040), 0.9f);

    private final GameSession s;
    private boolean teamOpen;
    private int openAt; // секунда игры, когда снять зоны команд; 0 — только действием
    private final Map<UUID, Long> lastDraw = new HashMap<>();
    private final Map<UUID, Long> lastWarn = new HashMap<>();

    PlayZone(GameSession s) { this.s = s; }

    private MapConfig map() { return s.map(); }

    public boolean active() {
        MapConfig m = map();
        return m != null && (!m.playZone.isEmpty() || (!teamOpen && !m.teamZones.isEmpty()));
    }

    void start() {
        teamOpen = false;
        MapConfig m = map();
        if (m == null) return;
        int lt = s.logic().teamZonesOpenAt();
        int t = lt >= 0 ? lt : m.teamZonesOpen >= 0 ? m.teamZonesOpen : s.mode.settings.getInt("team-zones-open", 0);
        openAt = Math.max(0, t);
        if (!m.teamZones.isEmpty() && openAt > 0)
            s.broadcast("<gray>Команды пока разделены — граница откроется через <yellow>" + Msg.time(openAt));
    }

    void tick() {
        if (!teamOpen && openAt > 0 && s.elapsed() >= openAt && !map().teamZones.isEmpty()) setTeamZones(true);
    }

    private int margin() { return Math.max(0, s.mode.settings.getInt("zone-margin", 0)); }

    /** Снять (true) или вернуть (false) границы между командами. */
    public void setTeamZones(boolean open) {
        if (teamOpen == open) return;
        teamOpen = open;
        if (map() == null || map().teamZones.isEmpty()) return;
        if (open) {
            s.broadcast("<gold><bold>Граница открыта!</bold> <yellow>Теперь можно пройти на сторону противника.");
            for (Player p : s.online()) p.playSound(p.getLocation(), Sound.BLOCK_IRON_DOOR_OPEN, 1f, 0.7f);
        } else {
            s.broadcast("<gray>Команды снова разделены.");
        }
    }

    public boolean teamZonesOpen() { return teamOpen; }

    /** Можно ли игроку быть в этой точке. */
    public boolean allowed(Player p, Location l) {
        MapConfig m = map();
        if (m == null || l.getWorld() == null || !l.getWorld().equals(s.gameWorld())) return true;
        if (!m.playZone.isEmpty() && !inAny(m.playZone, l)) return false;
        if (!teamOpen && !m.teamZones.isEmpty()) {
            String team = s.logic().zoneTeam(p);
            List<Region> tz = team == null ? null : m.teamZones.get(team.toLowerCase(Locale.ROOT));
            if (tz != null && !inAny(tz, l)) return false;
        }
        return true;
    }

    private boolean inAny(List<Region> l, Location loc) {
        int mg = margin();
        for (Region r : l) if (mg == 0 ? r.contains(loc) : r.containsLoose(loc, mg)) return true;
        return false;
    }

    /** Насколько игрок снаружи разрешённой зоны по горизонтали (0 — внутри или над/под ней). */
    private double outside(Player p, Location l) {
        MapConfig m = map();
        double d = 0;
        if (!m.playZone.isEmpty()) d = nearest(m.playZone, l);
        if (!teamOpen && !m.teamZones.isEmpty()) {
            String team = s.logic().zoneTeam(p);
            List<Region> tz = team == null ? null : m.teamZones.get(team.toLowerCase(Locale.ROOT));
            if (tz != null) d = Math.max(d, nearest(tz, l));
        }
        return d;
    }

    private double nearest(List<Region> l, Location loc) {
        double best = Double.MAX_VALUE;
        for (Region r : l) best = Math.min(best, r.outsideXZ(loc, margin()));
        return best;
    }

    public void onMove(PlayerMoveEvent e) {
        if (!active()) return;
        Player p = e.getPlayer();
        if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) return; // ждёт возрождения — летает где угодно
        Location to = e.getTo();
        if (allowed(p, to)) {
            drawEdges(p, to);
            return;
        }
        if (allowed(p, e.getFrom()) || outside(p, to) > outside(p, e.getFrom()) + 1e-6) {
            // барьер: дальше не пускаем; снаружи (отбросило) можно только идти обратно к зоне
            Location back = e.getFrom().clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            e.setTo(back);
            p.setVelocity(new org.bukkit.util.Vector(0, Math.min(0, p.getVelocity().getY()), 0));
            drawEdges(p, back);
            warn(p, "<red>Дальше нельзя");
        }
    }

    /** Эндер-жемчуг и т.п. за границу — отменяем. */
    public void onTeleport(PlayerTeleportEvent e) {
        if (!active()) return;
        var c = e.getCause();
        if (c != PlayerTeleportEvent.TeleportCause.ENDER_PEARL && c != PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT) return;
        if (!allowed(e.getPlayer(), e.getTo())) {
            e.setCancelled(true);
            warn(e.getPlayer(), "<red>Туда нельзя — это за границей");
        }
    }

    private void warn(Player p, String text) {
        long now = System.currentTimeMillis();
        Long t = lastWarn.get(p.getUniqueId());
        if (t != null && now - t < 1000) return;
        lastWarn.put(p.getUniqueId(), now);
        p.sendActionBar(Msg.parse(text));
    }

    /** Сетка частиц на ближайшей границе в пределах 3 блоков — не чаще 4 раз в секунду и только этому игроку. */
    private void drawEdges(Player p, Location at) {
        long now = System.currentTimeMillis();
        Long t = lastDraw.get(p.getUniqueId());
        if (t != null && now - t < 250) return;
        lastDraw.put(p.getUniqueId(), now);
        MapConfig m = map();
        List<Region> boxes = new ArrayList<>(m.playZone);
        boolean team = false;
        if (!teamOpen && !m.teamZones.isEmpty()) {
            String tn = s.logic().zoneTeam(p);
            List<Region> tz = tn == null ? null : m.teamZones.get(tn.toLowerCase(Locale.ROOT));
            if (tz != null) { boxes.addAll(tz); team = true; }
        }
        double x = at.getX(), y = at.getY() + 1, z = at.getZ();
        for (Region r : boxes) {
            int mg = margin();
            if (!r.containsLoose(at, mg)) continue;
            boolean isTeam = team && !m.playZone.contains(r);
            double[] faces = {r.minX() - mg, r.maxX() + 1 + mg, r.minZ() - mg, r.maxZ() + 1 + mg};
            for (int i = 0; i < 4; i++) {
                boolean alongX = i < 2;
                double d = Math.abs((alongX ? x : z) - faces[i]);
                if (d > 3) continue;
                // за этой гранью тоже можно (соседняя область зоны) — не граница
                Location beyond = alongX ? new Location(at.getWorld(), faces[i] + (i == 0 ? -0.5 : 0.5), at.getY(), z)
                        : new Location(at.getWorld(), x, at.getY(), faces[i] + (i == 2 ? -0.5 : 0.5));
                if (allowed(p, beyond)) continue;
                patch(p, alongX, faces[i], x, y, z, r, isTeam ? TEAM_EDGE : EDGE);
            }
        }
    }

    /** Квадрат 5×4 из точек на плоскости грани вокруг игрока, в пределах области. */
    private static void patch(Player p, boolean alongX, double plane, double x, double y, double z, Region r, Particle.DustOptions d) {
        for (int dy = -1; dy <= 2; dy++) {
            double py = Math.floor(y) + dy + 0.5;
            if (py < r.minY() || py > r.maxY() + 1) continue;
            for (int k = -2; k <= 2; k++) {
                if (alongX) {
                    double pz = Math.floor(z) + k + 0.5;
                    if (pz < r.minZ() || pz > r.maxZ() + 1) continue;
                    p.spawnParticle(Particle.DUST, plane, py, pz, 1, 0, 0, 0, 0, d);
                } else {
                    double px = Math.floor(x) + k + 0.5;
                    if (px < r.minX() || px > r.maxX() + 1) continue;
                    p.spawnParticle(Particle.DUST, px, py, plane, 1, 0, 0, 0, 0, d);
                }
            }
        }
    }

    void clear(Player p) {
        lastDraw.remove(p.getUniqueId());
        lastWarn.remove(p.getUniqueId());
    }

    void clearAll() {
        lastDraw.clear();
        lastWarn.clear();
        teamOpen = false;
    }
}
