package ru.mgp.ui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.mgp.MGPPlugin;
import ru.mgp.session.GameSession;
import ru.mgp.util.Msg;

import java.util.*;

/** Обновляет инфо-панель всем игрокам раз в секунду. Строки берутся из конфигов. */
public final class SidebarService {
    private final MGPPlugin plugin;
    private final Map<UUID, Sidebar> boards = new HashMap<>();

    /** Наведение на противника: зритель → на кого смотрит и до какого тика ник виден (осада, штурм). */
    private final Map<UUID, UUID> aimTarget = new HashMap<>();
    private final Map<UUID, Long> aimUntil = new HashMap<>();
    private long ticks;
    /** Сколько тиков ник ещё виден после того, как прицел ушёл (без мигания). */
    private static final int AIM_LINGER = 10;

    public SidebarService(MGPPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::aimTick, 20L, 2L);
    }

    /** Смотрит ли viewer прямо сейчас на target (луч из глаз, стены и блоки закрывают). */
    public boolean aimingAt(Player viewer, Player target) {
        Long until = aimUntil.get(viewer.getUniqueId());
        return until != null && until >= ticks && target.getUniqueId().equals(aimTarget.get(viewer.getUniqueId()));
    }

    /** Раз в 2 тика: на кого смотрит каждый игрок режима с «ником противника по наведению». */
    private void aimTick() {
        ticks += 2;
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            GameSession s = plugin.sessions().of(p);
            if (s == null || !s.isPlaying() || !s.isAlive(p) || !s.logic().aimNametags() || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                if (aimTarget.remove(u) != null) { aimUntil.remove(u); updateTeams(p, s); }
                continue;
            }
            double dist = s.mode.settings.getDouble("nametag-aim-distance", 64);
            var eye = p.getEyeLocation();
            var hit = p.getWorld().rayTrace(eye, eye.getDirection(), dist, org.bukkit.FluidCollisionMode.NEVER, true, 0.25,
                    e -> e instanceof Player o && !o.equals(p) && s.isAlive(o) && o.getGameMode() != org.bukkit.GameMode.SPECTATOR);
            UUID now = hit != null && hit.getHitEntity() instanceof Player o ? o.getUniqueId() : null;
            UUID before = aimTarget.get(u);
            boolean wasOn = before != null && aimUntil.getOrDefault(u, 0L) >= ticks - 2;
            if (now != null) {
                aimTarget.put(u, now);
                aimUntil.put(u, ticks + AIM_LINGER);
                if (!now.equals(before) || !wasOn) updateTeams(p, s);
            } else if (before != null && aimUntil.getOrDefault(u, 0L) < ticks) {
                aimTarget.remove(u);
                aimUntil.remove(u);
                updateTeams(p, s);
            }
        }
    }

    public void updateAll() {
        for (Player p : Bukkit.getOnlinePlayers()) update(p);
    }

    public void update(Player p) {
        Sidebar sb = boards.computeIfAbsent(p.getUniqueId(), k -> new Sidebar());
        if (p.getScoreboard() != sb.board()) p.setScoreboard(sb.board());
        GameSession s = plugin.sessions().of(p);
        String title;
        List<String> lines;
        Map<String, String> ph;
        if (s != null) {
            title = s.mode.sidebarTitle;
            lines = s.sidebarLines();
            ph = s.placeholders(p);
        } else {
            title = plugin.getConfig().getString("hub.sidebar.title", "<gold><bold>МИНИИГРЫ");
            lines = plugin.getConfig().getStringList("hub.sidebar.lines");
            ph = new HashMap<>();
            ph.put("online", String.valueOf(Bukkit.getOnlinePlayers().size()));
            ph.put("player", Msg.esc(p.getName()));
            for (var m : plugin.registry().modes()) ph.put("players_" + m.id, String.valueOf(plugin.sessions().playersIn(m.id)));
        }
        List<net.kyori.adventure.text.Component> out = new ArrayList<>();
        for (String line : lines) out.add(Msg.parse(line, ph));
        sb.update(Msg.parse(title, ph), out);
        updateTeams(p, s);
    }

    /** Цвета ников и скрытые ники для этого зрителя. */
    private void updateTeams(Player p, GameSession s) {
        Sidebar sb = boards.get(p.getUniqueId());
        if (sb == null) return;
        // прятки и т.п.: ники не видно ни через стены, ни вблизи — никакой лишней подсказки
        Set<String> hidden = new HashSet<>();
        if (s != null && s.isPlaying()) {
            boolean all = s.mode.settings.getBoolean("hide-nametags", false);
            // осада и т.п.: ники противников скрыты, союзников видно
            for (Player o : s.online()) if (all || !s.logic().nametagVisible(p, o)) hidden.add(o.getName());
        }
        sb.teams(s != null && (s.isPlaying() || s.state() == ru.mgp.session.GameSession.State.ENDING) ? s.logic().teamColors() : java.util.Map.of(), hidden);
    }


    public void remove(Player p) {
        boards.remove(p.getUniqueId());
        aimTarget.remove(p.getUniqueId());
        aimUntil.remove(p.getUniqueId());
    }
}
