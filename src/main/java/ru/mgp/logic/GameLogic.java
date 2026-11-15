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

    /** Удар игрока по сущности (до проверок ванили) — например, по хитбоксу маскировки. */
    public void onAttackEntity(io.papermc.paper.event.player.PrePlayerAttackEntityEvent e) {}

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
