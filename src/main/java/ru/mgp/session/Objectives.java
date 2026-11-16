package ru.mgp.session;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import ru.mgp.config.MapConfig.Objective;
import ru.mgp.util.Msg;
import ru.mgp.util.Region;

import java.util.*;

/**
 * Цели на разрушение (maps/<карта>.yml → objectives), например бункер или склад в режиме штурма.
 * В начале игры запоминаются все твёрдые блоки области; раз в секунду считается, сколько из них
 * уже нет (сломаны, взорваны, сгорели). Набрали percent% — действия цели выполняются один раз.
 */
public final class Objectives {
    /** Больше блоков в одной цели не проверяем: слишком дорого каждую секунду. */
    public static final int MAX_VOLUME = 400_000;

    private final GameSession s;
    private final List<State> states = new ArrayList<>();
    private final Map<UUID, BossBar> shown = new HashMap<>();

    private static final class State {
        final Objective o;
        final BitSet solid;
        final int total;
        int destroyed;
        boolean done;

        State(Objective o, BitSet solid) {
            this.o = o;
            this.solid = solid;
            this.total = solid.cardinality();
        }

        double percent() { return total == 0 ? 0 : destroyed * 100.0 / total; }
    }

    Objectives(GameSession s) { this.s = s; }

    private static int index(Region r, int x, int y, int z) {
        int dx = r.maxX() - r.minX() + 1, dz = r.maxZ() - r.minZ() + 1;
        return ((y - r.minY()) * dz + (z - r.minZ())) * dx + (x - r.minX());
    }

    private static long volume(Region r) {
        return (long) (r.maxX() - r.minX() + 1) * (r.maxY() - r.minY() + 1) * (r.maxZ() - r.minZ() + 1);
    }

    /** Запомнить исходные блоки (в начале игры, мир уже свежая копия шаблона). */
    void start() {
        states.clear();
        World w = s.gameWorld();
        if (w == null || s.map() == null) return;
        for (Objective o : s.map().objectives) {
            Region r = o.region();
            if (volume(r) > MAX_VOLUME) {
                s.plugin().getLogger().warning("Цель " + o.id() + " на карте " + s.map().id + ": область больше " + MAX_VOLUME + " блоков, пропускаю");
                continue;
            }
            BitSet b = new BitSet();
            for (int y = r.minY(); y <= r.maxY(); y++)
                for (int z = r.minZ(); z <= r.maxZ(); z++)
                    for (int x = r.minX(); x <= r.maxX(); x++)
                        if (counts(w.getBlockAt(x, y, z).getType())) b.set(index(r, x, y, z));
            State st = new State(o, b);
            if (st.total == 0) {
                s.plugin().getLogger().warning("Цель " + o.id() + " на карте " + s.map().id + ": в области нет блоков");
                continue;
            }
            states.add(st);
        }
    }

    /** Твёрдый блок постройки (стены, двери, стекло); трава, цветы, вода и огонь не считаются. */
    private static boolean counts(Material m) {
        return m.isSolid();
    }

    /** Раз в секунду, пока идёт игра. */
    void tick() {
        if (states.isEmpty()) return;
        World w = s.gameWorld();
        if (w == null) return;
        for (State st : states) {
            if (st.done) continue;
            Region r = st.o.region();
            int gone = 0;
            for (int i = st.solid.nextSetBit(0); i >= 0; i = st.solid.nextSetBit(i + 1)) {
                int dx = r.maxX() - r.minX() + 1, dz = r.maxZ() - r.minZ() + 1;
                int x = r.minX() + i % dx, z = r.minZ() + (i / dx) % dz, y = r.minY() + i / (dx * dz);
                if (!counts(w.getBlockAt(x, y, z).getType())) gone++;
            }
            st.destroyed = gone;
            if (st.percent() >= st.o.percent()) {
                st.done = true;
                s.broadcast(st.o.name() + " <gray>разрушен" + " <dark_gray>(" + Math.round(st.percent()) + "%)");
                ActionRunner.run(s, st.o.actions(), null);
            }
        }
        updateBars();
    }

    private void updateBars() {
        State cur = null;
        for (State st : states) if (!st.done && st.o.show()) { cur = st; break; }
        for (Player p : s.online()) {
            BossBar bar = shown.get(p.getUniqueId());
            if (cur == null) {
                if (bar != null) { p.hideBossBar(bar); shown.remove(p.getUniqueId()); }
                continue;
            }
            float prog = (float) Math.min(1, cur.percent() / cur.o.percent());
            String title = cur.o.name() + " <white>" + Math.round(cur.percent()) + "% <gray>/ " + Math.round(cur.o.percent()) + "%";
            if (bar == null) {
                bar = BossBar.bossBar(Msg.parse(title), prog, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
                shown.put(p.getUniqueId(), bar);
                p.showBossBar(bar);
            } else {
                bar.name(Msg.parse(title));
                bar.progress(prog);
            }
        }
    }

    /** Прогресс для /mgp и плейсхолдеров: id → проценты. */
    public Map<String, Double> progress() {
        Map<String, Double> m = new LinkedHashMap<>();
        for (State st : states) m.put(st.o.id(), st.percent());
        return m;
    }

    void clear(Player p) {
        BossBar bar = shown.remove(p.getUniqueId());
        if (bar != null) p.hideBossBar(bar);
    }

    void clearAll() {
        for (UUID u : new ArrayList<>(shown.keySet())) {
            Player p = org.bukkit.Bukkit.getPlayer(u);
            BossBar bar = shown.remove(u);
            if (p != null && bar != null) p.hideBossBar(bar);
        }
        states.clear();
    }
}
