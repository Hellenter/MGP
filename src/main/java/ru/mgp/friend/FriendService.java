package ru.mgp.friend;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.mgp.MGPPlugin;
import ru.mgp.util.Msg;

import java.io.File;
import java.util.*;

/**
 * Друзья в духе Hypixel (/f): запрос с кнопками в чате (действует friends.request-minutes), дружба взаимная
 * и хранится в plugins/MGP/friends.yml. Друзья видят, когда ты заходишь и выходишь, а в меню пати
 * их головы стоят первыми — пригласить можно в один клик.
 */
public final class FriendService implements Listener {
    public static final String F = "<green>Друзья <dark_gray>» <gray>";
    private static final String LINE = "<green><st>                                                    </st>";

    private final MGPPlugin plugin;
    private final File file;
    private final Map<UUID, LinkedHashSet<UUID>> friends = new HashMap<>();
    /** кому → (от кого → когда истекает) */
    private final Map<UUID, LinkedHashMap<UUID, Long>> requests = new HashMap<>();

    public FriendService(MGPPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "friends.yml");
    }

    public static void msg(Player p, String mm) { p.sendMessage(Msg.parse(F + mm)); }

    int max() { return Math.max(1, plugin.getConfig().getInt("friends.max", 100)); }
    long requestMillis() { return Math.max(1, plugin.getConfig().getInt("friends.request-minutes", 5)) * 60_000L; }
    boolean notifyJoin() { return plugin.getConfig().getBoolean("friends.join-messages", true); }

    // ------------------------------------------------------------------ хранение

    public void load() {
        friends.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String k : y.getKeys(false)) {
            UUID u = uuid(k);
            if (u == null) continue;
            LinkedHashSet<UUID> set = new LinkedHashSet<>();
            for (String s : y.getStringList(k)) { UUID f = uuid(s); if (f != null && !f.equals(u)) set.add(f); }
            if (!set.isEmpty()) friends.put(u, set);
        }
        // дружба взаимная: чиним односторонние записи после ручной правки файла
        for (var e : new ArrayList<>(friends.entrySet()))
            for (UUID f : e.getValue()) friends.computeIfAbsent(f, k -> new LinkedHashSet<>()).add(e.getKey());
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (var e : friends.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            List<String> l = new ArrayList<>();
            for (UUID f : e.getValue()) l.add(f.toString());
            y.set(e.getKey().toString(), l);
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (Exception ex) {
            plugin.getLogger().warning("Не удалось сохранить friends.yml: " + ex.getMessage());
        }
    }

    private static UUID uuid(String s) {
        try { return UUID.fromString(s.trim()); } catch (Exception e) { return null; }
    }

    // ------------------------------------------------------------------ запросы

    public Set<UUID> friends(UUID u) { return Collections.unmodifiableSet(friends.getOrDefault(u, new LinkedHashSet<>())); }

    public boolean areFriends(UUID a, UUID b) { return friends.getOrDefault(a, new LinkedHashSet<>()).contains(b); }

    /** Друзья: сначала онлайн (по нику), потом не в сети (по нику). */
    public List<UUID> sorted(UUID u) {
        List<UUID> l = new ArrayList<>(friends(u));
        l.sort(Comparator.comparing((UUID f) -> Bukkit.getPlayer(f) == null).thenComparing(f -> name(f).toLowerCase(Locale.ROOT)));
        return l;
    }

    /** Входящие запросы (кто прислал), просроченные убираются. */
    public List<UUID> incoming(UUID to) {
        var m = requests.get(to);
        if (m == null) return List.of();
        long now = System.currentTimeMillis();
        m.values().removeIf(t -> t < now);
        if (m.isEmpty()) requests.remove(to);
        return new ArrayList<>(m.keySet());
    }

    public boolean hasOutgoing(UUID from, UUID to) {
        var m = requests.get(to);
        Long t = m == null ? null : m.get(from);
        return t != null && t >= System.currentTimeMillis();
    }

    public static String name(UUID u) {
        OfflinePlayer o = Bukkit.getOfflinePlayer(u);
        return o.getName() != null ? o.getName() : u.toString().substring(0, 8);
    }

    /** Ник → UUID: сначала онлайн, потом друзья игрока, потом входящие запросы. */
    UUID resolve(Player p, String nick) {
        Player o = Bukkit.getPlayerExact(nick);
        if (o != null) return o.getUniqueId();
        for (UUID f : friends(p.getUniqueId())) if (name(f).equalsIgnoreCase(nick)) return f;
        for (UUID f : incoming(p.getUniqueId())) if (name(f).equalsIgnoreCase(nick)) return f;
        return null;
    }

    public void add(Player from, Player to) {
        UUID a = from.getUniqueId(), b = to.getUniqueId();
        if (a.equals(b)) { msg(from, "<red>Нельзя добавить в друзья самого себя."); return; }
        if (areFriends(a, b)) { msg(from, "<red>" + Msg.esc(to.getName()) + " уже у тебя в друзьях."); return; }
        // он уже звал нас — просто принимаем
        if (hasOutgoing(b, a)) { accept(from, to.getName()); return; }
        if (hasOutgoing(a, b)) { msg(from, "<red>Запрос для " + Msg.esc(to.getName()) + " уже отправлен, ждём ответа."); return; }
        if (friends(a).size() >= max()) { msg(from, "<red>У тебя уже максимум друзей (" + max() + ")."); return; }
        long until = System.currentTimeMillis() + requestMillis();
        requests.computeIfAbsent(b, k -> new LinkedHashMap<>()).put(a, until);
        int min = (int) (requestMillis() / 60_000);
        msg(from, "<gray>Запрос в друзья отправлен <yellow>" + Msg.esc(to.getName()) + "<gray>. У него " + min + " мин., чтобы принять.");
        String fn = from.getName();
        to.sendMessage(Msg.parse(LINE));
        to.sendMessage(Msg.parse("<yellow>" + Msg.esc(fn) + " <gray>хочет добавить тебя в друзья!"));
        to.sendMessage(Msg.parse("<click:run_command:'/friend accept " + fn + "'><hover:show_text:'<green>Добавить " + Msg.esc(fn) + " в друзья'><green><bold>[ПРИНЯТЬ]</bold></hover></click>"
                + "  <click:run_command:'/friend deny " + fn + "'><hover:show_text:'<red>Отказаться'><red><bold>[ОТКЛОНИТЬ]</bold></hover></click>"
                + "  <gray>" + min + " мин."));
        to.sendMessage(Msg.parse(LINE));
        to.playSound(to.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.4f);
    }

    public void accept(Player p, String fromName) {
        UUID me = p.getUniqueId();
        List<UUID> in = incoming(me);
        UUID from = null;
        if (fromName == null) { if (in.size() == 1) from = in.get(0); }
        else for (UUID u : in) if (name(u).equalsIgnoreCase(fromName)) from = u;
        if (from == null) {
            msg(p, in.isEmpty() ? "<red>Нет запросов в друзья (или они истекли)."
                    : "<red>Укажи ник: <yellow>/f accept <ник>");
            return;
        }
        if (friends(me).size() >= max()) { msg(p, "<red>У тебя уже максимум друзей (" + max() + ")."); return; }
        requests.get(me).remove(from);
        var back = requests.get(from);
        if (back != null) back.remove(me);
        friends.computeIfAbsent(me, k -> new LinkedHashSet<>()).add(from);
        friends.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(me);
        save();
        msg(p, "<green>Теперь вы с <yellow>" + Msg.esc(name(from)) + " <green>друзья!");
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
        Player f = Bukkit.getPlayer(from);
        if (f != null) {
            msg(f, "<yellow>" + Msg.esc(p.getName()) + " <green>принял запрос — теперь вы друзья!");
            f.playSound(f.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
        }
    }

    public void deny(Player p, String fromName) {
        UUID me = p.getUniqueId();
        List<UUID> in = incoming(me);
        UUID from = null;
        if (fromName == null) { if (in.size() == 1) from = in.get(0); }
        else for (UUID u : in) if (name(u).equalsIgnoreCase(fromName)) from = u;
        if (from == null) { msg(p, "<red>Нет такого запроса в друзья."); return; }
        requests.get(me).remove(from);
        msg(p, "<gray>Запрос от " + Msg.esc(name(from)) + " отклонён.");
        Player f = Bukkit.getPlayer(from);
        if (f != null) msg(f, "<yellow>" + Msg.esc(p.getName()) + " <gray>отклонил запрос в друзья.");
    }

    public void remove(Player p, UUID other) {
        UUID me = p.getUniqueId();
        if (other == null || !areFriends(me, other)) { msg(p, "<red>Этого игрока нет у тебя в друзьях."); return; }
        friends.getOrDefault(me, new LinkedHashSet<>()).remove(other);
        friends.getOrDefault(other, new LinkedHashSet<>()).remove(me);
        save();
        msg(p, "<gray>" + Msg.esc(name(other)) + " удалён из друзей.");
        Player o = Bukkit.getPlayer(other);
        if (o != null) msg(o, "<yellow>" + Msg.esc(p.getName()) + " <gray>удалил тебя из друзей.");
    }

    public void list(Player p) {
        List<UUID> l = sorted(p.getUniqueId());
        p.sendMessage(Msg.parse(LINE));
        if (l.isEmpty()) p.sendMessage(Msg.parse("<gray>У тебя пока нет друзей. <yellow>/f add <ник>"));
        else {
            long on = l.stream().filter(u -> Bukkit.getPlayer(u) != null).count();
            p.sendMessage(Msg.parse("<green>Друзья <gray>(" + on + " в сети из " + l.size() + "):"));
            for (UUID u : l) {
                Player o = Bukkit.getPlayer(u);
                String n = Msg.esc(name(u));
                if (o == null) p.sendMessage(Msg.parse(" <dark_gray>● <gray>" + n + " <dark_gray>— не в сети"));
                else p.sendMessage(Msg.parse(" <green>● <white>" + n + " <gray>— " + status(o)
                        + "  <click:run_command:'/party invite " + o.getName() + "'><hover:show_text:'<green>Пригласить в пати'><aqua>[В пати]</hover></click>"));
            }
        }
        List<UUID> in = incoming(p.getUniqueId());
        if (!in.isEmpty()) {
            p.sendMessage(Msg.parse(""));
            p.sendMessage(Msg.parse("<yellow>Запросы в друзья:"));
            for (UUID u : in) {
                String n = name(u);
                p.sendMessage(Msg.parse(" <white>" + Msg.esc(n) + "  <click:run_command:'/friend accept " + n + "'><green>[ПРИНЯТЬ]</click> <click:run_command:'/friend deny " + n + "'><red>[ОТКЛОНИТЬ]</click>"));
            }
        }
        p.sendMessage(Msg.parse(LINE));
    }

    String status(Player o) {
        var s = plugin.sessions().of(o);
        if (s == null) return "<green>в хабе";
        return (s.isPlaying() ? "<gold>играет: " : "<yellow>ждёт игру: ") + s.mode.displayName;
    }

    // ------------------------------------------------------------------ вход / выход

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (notifyJoin()) for (UUID f : friends(p.getUniqueId())) {
            Player o = Bukkit.getPlayer(f);
            if (o != null) o.sendMessage(Msg.parse(F + "<yellow>" + Msg.esc(p.getName()) + " <green>зашёл на сервер."));
        }
        int n = incoming(p.getUniqueId()).size();
        if (n > 0) Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) p.sendMessage(Msg.parse(F + "<yellow>Запросов в друзья: " + n + ". <click:run_command:'/friend list'><aqua>[Посмотреть]</click>"));
        }, 40L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (!notifyJoin()) return;
        for (UUID f : friends(p.getUniqueId())) {
            Player o = Bukkit.getPlayer(f);
            if (o != null && !o.equals(p)) o.sendMessage(Msg.parse(F + "<yellow>" + Msg.esc(p.getName()) + " <gray>вышел с сервера."));
        }
    }
}
