package ru.mgp.session;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import ru.mgp.MGPPlugin;
import ru.mgp.config.MapConfig;
import ru.mgp.config.ModeConfig;
import ru.mgp.util.Msg;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Подбор игроку сессии (очередь), учёт всех сессий и общий таймер (раз в секунду). */
public final class SessionManager {
    private final MGPPlugin plugin;
    private final List<GameSession> sessions = new ArrayList<>();
    private final Map<UUID, GameSession> byPlayer = new ConcurrentHashMap<>();
    private int nextId = 1;
    private BukkitTask task;

    public SessionManager(MGPPlugin plugin) { this.plugin = plugin; }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private void tick() {
        for (GameSession s : List.copyOf(sessions)) {
            try {
                s.tick();
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка в сессии #" + s.id, e);
            }
        }
        plugin.sidebars().updateAll();
    }

    /** Найти подходящую сессию режима или создать новую. */
    public void join(Player p, String modeId) {
        ModeConfig mode = plugin.registry().mode(modeId);
        if (mode == null) {
            Msg.send(p, "<red>Режим не найден: " + Msg.esc(modeId));
            return;
        }
        String problem = problem(mode, p.hasPermission("mgp.admin"));
        if (problem != null) {
            Msg.send(p, problem);
            return;
        }
        GameSession current = of(p);
        if (current != null) current.removePlayer(p, false);

        GameSession best = null;
        for (GameSession s : sessions) {
            if (!s.mode.id.equals(mode.id) || !s.canJoin()) continue;
            if (best == null || s.size() > best.size()) best = s; // сначала заполняем самые полные лобби
        }
        if (best == null) {
            long count = sessions.stream().filter(s -> s.mode.id.equals(mode.id)).count();
            if (mode.maxSessions > 0 && count >= mode.maxSessions) {
                Msg.send(p, "<red>Все арены заняты, попробуй чуть позже.");
                plugin.hub().send(p);
                return;
            }
            best = new GameSession(plugin, nextId++, mode);
            sessions.add(best);
            best.create();
        }
        if (!best.addPlayer(p)) {
            Msg.send(p, best.state() == GameSession.State.CLOSED
                    ? "<red>Не удалось подготовить арену" + (p.hasPermission("mgp.admin") ? " — причина в консоли сервера (logs/latest.log)." : ", попробуй позже.")
                    : "<red>Не удалось присоединиться: арена уже заполнена или стартует. Попробуй ещё раз.");
            plugin.hub().send(p);
        }
    }

    /** Карта годится для игры: есть шаблон и есть куда ставить игроков (спавны или участки). */
    public boolean playable(MapConfig m) {
        return plugin.worlds().templateExists(m.template) && (!m.spawns.isEmpty() || !m.plots.isEmpty());
    }

    /** Почему в режим нельзя зайти, или null. Админу — с подробностями по каждой карте. */
    private String problem(ModeConfig mode, boolean admin) {
        List<MapConfig> maps = plugin.registry().mapsFor(mode.id);
        for (MapConfig m : maps) if (playable(m)) return null;
        if (!admin) return "<red>Режим " + mode.displayName + " <red>пока не готов: нет ни одной карты.";
        if (maps.isEmpty()) {
            return "<red>У режима " + mode.id + " нет карт. Добавь: /mgp import <карта> " + mode.id + " <x1 y1 z1 x2 y2 z2>";
        }
        StringBuilder sb = new StringBuilder("<red>У режима " + mode.id + " нет готовых карт:");
        for (MapConfig m : maps) {
            sb.append("\n<gray>• ").append(m.id).append(": ");
            if (!plugin.worlds().templateExists(m.template)) sb.append("<red>нет шаблона templates/").append(Msg.esc(m.template));
            else sb.append("<red>нет спавнов</red> <gray>— встань на точку и /mgp setup spawn ").append(m.id);
        }
        return sb.toString();
    }

    public void leave(Player p) {
        GameSession s = of(p);
        if (s != null) s.removePlayer(p, true);
        else plugin.hub().send(p);
    }

    public void quit(Player p) {
        GameSession s = of(p);
        if (s != null) s.removePlayer(p, false);
    }

    void bind(Player p, GameSession s) { byPlayer.put(p.getUniqueId(), s); }
    void unbind(Player p) { byPlayer.remove(p.getUniqueId()); }
    void remove(GameSession s) { sessions.remove(s); }

    public GameSession of(Player p) { return byPlayer.get(p.getUniqueId()); }
    public GameSession of(UUID u) { return byPlayer.get(u); }

    public GameSession byWorld(World w) {
        for (GameSession s : sessions) if (s.owns(w)) return s;
        return null;
    }

    public List<GameSession> sessions() { return Collections.unmodifiableList(sessions); }

    public int playersIn(String modeId) {
        int n = 0;
        for (GameSession s : sessions) if (s.mode.id.equals(modeId)) n += s.size();
        return n;
    }

    public void shutdown() {
        if (task != null) task.cancel();
        plugin.worlds().setShuttingDown();
        for (GameSession s : List.copyOf(sessions)) s.close();
    }
}
