package ru.mgp.logic;

import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import ru.mgp.session.GameSession;

import java.util.List;
import java.util.Map;

/**
 * Логика конкретного режима поверх общего «движка» сессии.
 * События вызываются только для живых игроков этой сессии во время игры (state = PLAYING).
 */
public abstract class GameLogic {
    protected final GameSession session;

    protected GameLogic(GameSession session) { this.session = session; }

    /** Добавить свои голосования (например, тема в Билд баттле). */
    public void registerVotes() {}

    /** Голосования закрыты (за ~10 сек до старта). */
    public void onVotesClosed() {}

    /** Игра началась: расставить игроков, выдать предметы. */
    public abstract void onStart();

    /** Раз в секунду во время игры. */
    public void onTick() {}

    /** Истёк time-limit режима. */
    public void onTimeUp() { session.end(List.of(), "<yellow>Время вышло!"); }

    public void onLeave(Player p, boolean wasAlive) {}
    public void onDamage(EntityDamageEvent e) {}
    public void onDeath(PlayerDeathEvent e) {}
    public void onBlockBreak(BlockBreakEvent e) {}
    public void onBlockPlace(BlockPlaceEvent e) {}
    public void onBucket(PlayerBucketEmptyEvent e) {}
    public void onFlow(BlockFromToEvent e) {}
    public void onCreatureSpawn(CreatureSpawnEvent e) {}
    public void onMove(PlayerMoveEvent e) {}
    public void onInteract(PlayerInteractEvent e) {}
    /** Игрок выбрасывает предмет (Q или из инвентаря). */
    public void onDrop(org.bukkit.event.player.PlayerDropItemEvent e) {}
    /** Взрыв в мире игры (ТНТ, крипер...). */
    public void onExplode(org.bukkit.event.entity.EntityExplodeEvent e) {}

    /** Есть ли у режима свои зоны по ходу игры (stageZone), например зоны стадий штурма. */
    public boolean customZones() { return false; }

    /** Где сейчас может быть игрок сверх play-зоны (например, зона его команды на текущей стадии); null — без ограничения. */
    public java.util.List<ru.mgp.util.Region> stageZone(Player p) { return null; }

    /** Не держать игрока в play-зоне и зоне команды (например, он в лимбо штурма). */
    public boolean zoneFree(Player p) { return false; }

    /** Взрыв без сущности-источника (например, разрывная стрела без стрелка). */
    public void onBlockExplode(org.bukkit.event.block.BlockExplodeEvent e) {}

    /** Монеты на счёте игрока (товары магазинов с price: "MONEY n"); -1 — в режиме нет экономики. */
    public int balance(Player p) { return -1; }

    /** Списать монеты со счёта; false — не хватает или экономики нет. */
    public boolean spend(Player p, int amount) { return false; }

    /** Считается ли сейчас цель на разрушение (objectives): например, только цели текущей стадии штурма. */
    public boolean objectiveActive(String id) { return true; }

    /** Цель на разрушение разрушена (после её actions). */
    public void onObjectiveDestroyed(ru.mgp.config.MapConfig.Objective o) {}

    /** Можно ли сейчас взрывать (разрывные стрелы); например, не во время подготовки осады. */
    public boolean explosivesAllowed() { return true; }

    /** Где возродить игрока после смерти (null — точка наблюдателя). */
    public org.bukkit.Location respawnLocation(Player p) { return null; }

    /** Цвет ника по командам: ник → цвет (пусто — без команд). */
    public Map<String, net.kyori.adventure.text.format.NamedTextColor> teamColors() { return Map.of(); }

    /** Команда игрока для зон карты (team-zones) и спавнов: например attackers / defenders; null — без команды. */
    public String zoneTeam(Player p) { return null; }

    /** Удар игрока по сущности (до проверок ванили) — например, по хитбоксу маскировки. */
    public void onAttackEntity(io.papermc.paper.event.player.PrePlayerAttackEntityEvent e) {}

    /** Видит ли viewer ник target над головой (осада: ники противников скрыты). */
    public boolean nametagVisible(Player viewer, Player target) { return true; }

    /** Считать, на кого смотрит каждый игрок (ник противника виден только по наведению — см. nametagVisible). */
    public boolean aimNametags() { return false; }

    /** Через сколько секунд после старта снять зоны команд (-1 — как в карте/режиме, team-zones-open). */
    public int teamZonesOpenAt() { return -1; }

    /** Можно ли выбирать набор в лобби ожидания (false — режим сам выдаёт выбор после старта). */
    public boolean kitsInLobby() { return true; }

    /** Дополнительное правило видимости между игроками одной идущей игры. */
    public boolean canSee(Player viewer, Player target) { return true; }

    /** Нажатие служебного предмета с меткой "logic:<payload>". */
    public void onHotbar(Player p, String payload) {}

    /** Действие "mode: ..." из таймлайна/триггеров. Вернуть false, если неизвестно. */
    public boolean handleAction(String action, Player target) { return false; }

    /** Свои плейсхолдеры для инфо-панели. */
    public void placeholders(Player p, Map<String, String> ph) {}

    /** Имя набора строк инфо-панели из sidebar.* (null — "playing"). */
    public String sidebarGroup() { return null; }

    public void onEnd() {}
}
