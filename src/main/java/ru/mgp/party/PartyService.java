package ru.mgp.party;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import ru.mgp.MGPPlugin;
import ru.mgp.session.GameSession;
import ru.mgp.util.Msg;

import java.util.*;

/**
 * Пати в духе Hypixel: /p invite, приглашение действует 60 секунд и принимается кликом в чате,
 * игры запускает только лидер — вся пати попадает в одну арену. Вышедший с сервера остаётся
 * в пати несколько минут (party.offline-minutes), лидерство при выходе лидера сразу переходит к следующему.
 */
public final class PartyService {
    public static final String P = "<blue>Пати <dark_gray>» <gray>";
    private static final String LINE = "<blue><st>                                                    </st>";

    private final MGPPlugin plugin;
    private final Map<UUID, Party> byPlayer = new HashMap<>();
    private final Map<UUID, BukkitTask> offlineKick = new HashMap<>();

    public PartyService(MGPPlugin plugin) { this.plugin = plugin; }

    /** Сообщение с префиксом пати (без общего префикса плагина). */
    public static void msg(Player p, String mm) { p.sendMessage(Msg.parse(P + mm)); }

    int maxSize() { return Math.max(2, plugin.getConfig().getInt("party.max-size", 8)); }
    int inviteSeconds() { return Math.max(10, plugin.getConfig().getInt("party.invite-seconds", 60)); }

    public Party of(Player p) { return byPlayer.get(p.getUniqueId()); }
    public Party of(UUID u) { return byPlayer.get(u); }

    /** Онлайн-участники пати (включая лидера), лидер первым. */
    public List<Player> online(Party party) {
        List<Player> out = new ArrayList<>();
        Player l = Bukkit.getPlayer(party.leader);
        if (l != null) out.add(l);
        for (UUID u : party.members) {
            if (u.equals(party.leader)) continue;
            Player p = Bukkit.getPlayer(u);
            if (p != null) out.add(p);
        }
        return out;
    }

    static String name(UUID u) {
        OfflinePlayer o = Bukkit.getOfflinePlayer(u);
        return o.getName() != null ? o.getName() : u.toString().substring(0, 8);
    }

    void tell(Party party, String mm) {
        for (Player p : online(party)) p.sendMessage(Msg.parse(P + mm));
    }

    private static void boxed(Player p, String... lines) {
        p.sendMessage(Msg.parse(LINE));
        for (String l : lines) p.sendMessage(Msg.parse(l));
        p.sendMessage(Msg.parse(LINE));
    }

    // ------------------------------------------------------------------ действия

    public void invite(Player from, Player to) {
        if (to.equals(from)) { PartyService.msg(from, "<red>Нельзя пригласить самого себя."); return; }
        Party party = of(from);
        if (party != null && !party.isLeader(from.getUniqueId())) { PartyService.msg(from, "<red>Приглашать может только лидер пати."); return; }
        Party other = of(to);
        if (other != null && other == party) { PartyService.msg(from, "<red>" + to.getName() + " уже в твоей пати."); return; }
        if (party == null) {
            party = new Party(from.getUniqueId());
            byPlayer.put(from.getUniqueId(), party);
        }
        if (party.size() + party.invites.size() >= maxSize()) { PartyService.msg(from, "<red>Пати заполнена (максимум " + maxSize() + ")."); return; }
        if (party.invites.containsKey(to.getUniqueId())) { PartyService.msg(from, "<red>" + to.getName() + " уже приглашён, ждём ответа."); return; }
        long until = System.currentTimeMillis() + inviteSeconds() * 1000L;
        party.invites.put(to.getUniqueId(), until);
        tell(party, "<yellow>" + Msg.esc(from.getName()) + " <gray>пригласил <yellow>" + Msg.esc(to.getName())
                + "<gray> в пати. У него " + inviteSeconds() + " секунд, чтобы принять.");
        String fn = from.getName();
        boxed(to, "<yellow>" + Msg.esc(fn) + " <gray>приглашает тебя в пати!",
                "<click:run_command:'/party accept " + fn + "'><hover:show_text:'<green>Вступить в пати " + Msg.esc(fn) + "'><green><bold>[ПРИНЯТЬ]</bold></hover></click>"
                        + "  <click:run_command:'/party deny " + fn + "'><hover:show_text:'<red>Отказаться'><red><bold>[ОТКЛОНИТЬ]</bold></hover></click>"
                        + "  <gray>" + inviteSeconds() + " сек.");
        UUID toId = to.getUniqueId();
        Party pt = party;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Long u = pt.invites.get(toId);
            if (u == null || u != until) return;
            pt.invites.remove(toId);
            tell(pt, "<gray>Приглашение для <yellow>" + Msg.esc(name(toId)) + " <gray>истекло.");
            Player t = Bukkit.getPlayer(toId);
            if (t != null) PartyService.msg(t, "<gray>Приглашение в пати " + Msg.esc(name(pt.leader)) + " истекло.");
            disbandIfEmpty(pt);
        }, inviteSeconds() * 20L);
    }

    /** Найти пати, пригласившую игрока: по нику пригласившего (лидера) или любую, если ник не указан. */
    private Party invitedBy(Player p, String leaderName) {
        long now = System.currentTimeMillis();
        for (Party party : new HashSet<>(byPlayer.values())) {
            Long until = party.invites.get(p.getUniqueId());
            if (until == null || until < now) continue;
            if (leaderName == null || name(party.leader).equalsIgnoreCase(leaderName)) return party;
        }
        return null;
    }

    public void accept(Player p, String leaderName) {
        Party party = invitedBy(p, leaderName);
        if (party == null) { PartyService.msg(p, "<red>Нет приглашения" + (leaderName != null ? " от " + Msg.esc(leaderName) : "") + " (или оно истекло)."); return; }
        Party old = of(p);
        if (old != null) leave(p);
        party.invites.remove(p.getUniqueId());
        if (party.size() >= maxSize()) { PartyService.msg(p, "<red>Пати уже заполнена."); return; }
        party.members.add(p.getUniqueId());
        byPlayer.put(p.getUniqueId(), party);
        tell(party, "<yellow>" + Msg.esc(p.getName()) + " <green>вступил в пати! <gray>(" + party.size() + "/" + maxSize() + ")");
        PartyService.msg(p, "<gray>Игры запускает лидер: когда " + Msg.esc(name(party.leader)) + " зайдёт в режим, ты попадёшь в ту же арену. Чат пати: /pc <текст>");
    }

    public void deny(Player p, String leaderName) {
        Party party = invitedBy(p, leaderName);
        if (party == null) { PartyService.msg(p, "<red>Нет приглашения" + (leaderName != null ? " от " + Msg.esc(leaderName) : "") + "."); return; }
        party.invites.remove(p.getUniqueId());
        PartyService.msg(p, "<gray>Ты отклонил приглашение " + Msg.esc(name(party.leader)) + ".");
        tell(party, "<yellow>" + Msg.esc(p.getName()) + " <gray>отклонил приглашение.");
        disbandIfEmpty(party);
    }

    public void leave(Player p) {
        Party party = of(p);
        if (party == null) { PartyService.msg(p, "<red>Ты не в пати."); return; }
        removeMember(party, p.getUniqueId(), "<yellow>" + Msg.esc(p.getName()) + " <gray>покинул пати.");
        PartyService.msg(p, "<gray>Ты вышел из пати.");
    }

    public void kick(Player leader, String target) {
        Party party = leaderParty(leader);
        if (party == null) return;
        UUID u = find(party, target);
        if (u == null || u.equals(leader.getUniqueId())) { PartyService.msg(leader, "<red>В пати нет игрока " + Msg.esc(target) + "."); return; }
        Player t = Bukkit.getPlayer(u);
        removeMember(party, u, "<yellow>" + Msg.esc(name(u)) + " <gray>исключён из пати.");
        if (t != null) PartyService.msg(t, "<red>Тебя исключили из пати " + Msg.esc(leader.getName()) + ".");
    }

    public void transfer(Player leader, String target) {
        Party party = leaderParty(leader);
        if (party == null) return;
        UUID u = find(party, target);
        if (u == null || u.equals(leader.getUniqueId())) { PartyService.msg(leader, "<red>В пати нет игрока " + Msg.esc(target) + "."); return; }
        party.leader = u;
        tell(party, "<gray>Лидер пати теперь <yellow>" + Msg.esc(name(u)) + "<gray>.");
    }

    public void disband(Player leader) {
        Party party = leaderParty(leader);
        if (party == null) return;
        tell(party, "<red>" + Msg.esc(leader.getName()) + " распустил пати.");
        dissolve(party);
    }

    public void list(Player p) {
        Party party = of(p);
        if (party == null) { PartyService.msg(p, "<gray>Ты не в пати. Пригласить друга: <yellow>/p <ник>"); return; }
        List<String> lines = new ArrayList<>();
        lines.add("<blue>Пати <gray>(" + party.size() + "/" + maxSize() + ")");
        lines.add("<gray>Лидер: " + status(party.leader) + Msg.esc(name(party.leader)));
        StringBuilder sb = new StringBuilder();
        for (UUID u : party.members) {
            if (u.equals(party.leader)) continue;
            if (sb.length() > 0) sb.append("<gray>, ");
            sb.append(status(u)).append(Msg.esc(name(u)));
        }
        if (sb.length() > 0) lines.add("<gray>Участники: " + sb);
        if (!party.invites.isEmpty()) {
            StringBuilder inv = new StringBuilder();
            for (UUID u : party.invites.keySet()) inv.append(inv.length() > 0 ? ", " : "").append(Msg.esc(name(u)));
            lines.add("<gray>Приглашены: <white>" + inv);
        }
        boxed(p, lines.toArray(String[]::new));
    }

    private String status(UUID u) {
        Player p = Bukkit.getPlayer(u);
        if (p == null) return "<red>● ";
        GameSession s = plugin.sessions().of(p);
        return s != null && s.isPlaying() ? "<gold>● " : "<green>● ";
    }

    public void chat(Player p, String text) {
        Party party = of(p);
        if (party == null) { PartyService.msg(p, "<red>Ты не в пати."); return; }
        String line = "<blue>Пати <dark_gray>» " + (party.isLeader(p.getUniqueId()) ? "<gold>★ " : "") + "<white>" + Msg.esc(p.getName()) + "<gray>: <white>" + Msg.esc(text);
        for (Player m : online(party)) m.sendMessage(Msg.parse(line));
    }

    /** /p warp: лидер в лобби ожидания — тянем туда пати; лидер в хабе — отправляем пати в хаб. */
    public void warp(Player leader) {
        Party party = leaderParty(leader);
        if (party == null) return;
        GameSession s = plugin.sessions().of(leader);
        if (s != null && s.isPlaying()) { PartyService.msg(leader, "<red>Ты в игре — собрать пати можно в лобби ожидания или в хабе."); return; }
        if (s == null) {
            for (Player m : online(party)) {
                if (m.equals(leader)) continue;
                GameSession ms = plugin.sessions().of(m);
                if (ms != null && ms.isPlaying()) { PartyService.msg(leader, "<gray>" + Msg.esc(m.getName()) + " сейчас в игре."); continue; }
                plugin.sessions().leave(m);
                PartyService.msg(m, "<gray>Лидер собрал пати в хабе.");
            }
            tell(party, "<gray>Пати собрана в хабе.");
        } else {
            plugin.sessions().join(leader, s.mode.id);
        }
    }

    // ------------------------------------------------------------------ события

    public void onQuit(Player p) {
        Party party = of(p);
        if (party == null) return;
        party.invites.remove(p.getUniqueId());
        if (party.isLeader(p.getUniqueId())) {
            for (UUID u : party.members) {
                if (!u.equals(p.getUniqueId()) && Bukkit.getPlayer(u) != null) {
                    party.leader = u;
                    tell(party, "<yellow>" + Msg.esc(p.getName()) + " <gray>вышел с сервера. Лидер пати теперь <yellow>" + Msg.esc(name(u)) + "<gray>.");
                    break;
                }
            }
        }
        int min = Math.max(0, plugin.getConfig().getInt("party.offline-minutes", 5));
        tell(party, "<yellow>" + Msg.esc(p.getName()) + " <gray>вышел с сервера" + (min > 0 ? " — через " + min + " мин. он покинет пати, если не вернётся." : "."));
        UUID u = p.getUniqueId();
        BukkitTask old = offlineKick.remove(u);
        if (old != null) old.cancel();
        offlineKick.put(u, Bukkit.getScheduler().runTaskLater(plugin, () -> {
            offlineKick.remove(u);
            Party pt = of(u);
            if (pt != null && Bukkit.getPlayer(u) == null) removeMember(pt, u, "<yellow>" + Msg.esc(name(u)) + " <gray>не вернулся и покинул пати.");
        }, Math.max(1, min * 60L * 20L)));
    }

    public void onJoin(Player p) {
        BukkitTask t = offlineKick.remove(p.getUniqueId());
        if (t != null) t.cancel();
        Party party = of(p);
        if (party != null) tell(party, "<yellow>" + Msg.esc(p.getName()) + " <gray>вернулся.");
    }

    // ------------------------------------------------------------------ внутреннее

    private Party leaderParty(Player p) {
        Party party = of(p);
        if (party == null) { PartyService.msg(p, "<red>Ты не в пати."); return null; }
        if (!party.isLeader(p.getUniqueId())) { PartyService.msg(p, "<red>Это может только лидер пати."); return null; }
        return party;
    }

    private UUID find(Party party, String name) {
        for (UUID u : party.members) if (name(u).equalsIgnoreCase(name)) return u;
        return null;
    }

    private void removeMember(Party party, UUID u, String msg) {
        tell(party, msg);
        party.members.remove(u);
        byPlayer.remove(u);
        if (party.leader.equals(u) && !party.members.isEmpty()) {
            UUID next = party.members.stream().filter(m -> Bukkit.getPlayer(m) != null).findFirst().orElse(party.members.iterator().next());
            party.leader = next;
            tell(party, "<gray>Лидер пати теперь <yellow>" + Msg.esc(name(next)) + "<gray>.");
        }
        disbandIfEmpty(party);
    }

    /** В пати один человек и никого не ждём — распускаем (как на Hypixel). */
    private void disbandIfEmpty(Party party) {
        if (party.members.size() > 1 || !party.invites.isEmpty()) return;
        if (party.members.size() == 1) tell(party, "<gray>Пати распущена: в ней никого не осталось.");
        dissolve(party);
    }

    private void dissolve(Party party) {
        for (UUID u : party.members) byPlayer.remove(u);
        party.members.clear();
        party.invites.clear();
    }
}
