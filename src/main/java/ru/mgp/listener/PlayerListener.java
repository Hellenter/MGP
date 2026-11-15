package ru.mgp.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import ru.mgp.MGPPlugin;
import ru.mgp.session.GameSession;
import ru.mgp.ui.GameSelectorMenu;
import ru.mgp.ui.Hotbar;
import ru.mgp.ui.KitMenu;
import ru.mgp.ui.VoteMenu;
import ru.mgp.util.Keys;

/** Вход/выход, служебные предметы хотбара, изоляция чата, защита хаба. */
public final class PlayerListener implements Listener {
    private final MGPPlugin plugin;

    public PlayerListener(MGPPlugin plugin) { this.plugin = plugin; }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        plugin.hub().send(e.getPlayer());
        plugin.sidebars().update(e.getPlayer());
        plugin.parties().onJoin(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.sessions().quit(e.getPlayer());
        plugin.sidebars().remove(e.getPlayer());
        plugin.parties().onQuit(e.getPlayer());
    }

    // ------------------------------------------------------------- служебные предметы

    @EventHandler(priority = EventPriority.LOW)
    public void onUse(PlayerInteractEvent e) {
        String tag = Keys.tag(e.getItem());
        if (tag == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        GameSession s = plugin.sessions().of(p);
        switch (tag) {
            case Hotbar.SELECTOR -> new GameSelectorMenu(plugin).open(p);
            case Hotbar.PARTY -> {
                Long t = partyClick.get(p.getUniqueId());
                if (t == null || System.currentTimeMillis() - t > 300) new ru.mgp.party.PartyMenu(plugin, p).open(p);
            }
            case Hotbar.LEAVE -> plugin.sessions().leave(p);
            case Hotbar.VOTE -> { if (s != null) VoteMenu.openFor(p, s); }
            case Hotbar.KIT -> { if (s != null) new KitMenu(s, p).open(p); }
            case Hotbar.AGAIN -> { if (s != null) plugin.sessions().join(p, s.mode.id); }
            default -> {
                if (tag.startsWith("logic:") && s != null && s.isPlaying()) s.logic().onHotbar(p, tag.substring(6));
                else if (tag.startsWith(ru.mgp.network.LinkedServers.TAG_PREFIX))
                    plugin.servers().transfer(p, tag.substring(ru.mgp.network.LinkedServers.TAG_PREFIX.length()));
            }
        }
    }

    private final java.util.Map<java.util.UUID, Long> partyClick = new java.util.HashMap<>();

    /** ПКМ предметом «Пати» по игроку — пригласить его. */
    @EventHandler(priority = EventPriority.LOW)
    public void onPartyItemOnPlayer(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !(e.getRightClicked() instanceof Player target)) return;
        Player p = e.getPlayer();
        if (!Hotbar.PARTY.equals(Keys.tag(p.getInventory().getItemInMainHand()))) return;
        e.setCancelled(true);
        partyClick.put(p.getUniqueId(), System.currentTimeMillis()); // клиент следом шлёт «ПКМ в воздух» — меню не открываем
        if (target.hasMetadata("NPC")) return;
        plugin.parties().invite(p, target);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (Keys.tag(e.getCurrentItem()) != null) { e.setCancelled(true); return; }
        if (e.getClick() == ClickType.NUMBER_KEY && e.getWhoClicked() instanceof Player p
                && Keys.tag(p.getInventory().getItem(e.getHotbarButton())) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (Keys.tag(e.getItemDrop().getItemStack()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (Keys.tag(e.getMainHandItem()) != null || Keys.tag(e.getOffHandItem()) != null) e.setCancelled(true);
    }

    /**
     * Админ в хабе перешёл в креатив — убираем предметы режимов, чтобы не мешали строить.
     * Вернулся из креатива — выдаём их снова (если не выключены через /mgp hotbar off).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        Player p = e.getPlayer();
        if (plugin.sessions().of(p) != null) return;
        org.bukkit.GameMode to = e.getNewGameMode(), from = p.getGameMode();
        if (to == org.bukkit.GameMode.CREATIVE) {
            plugin.getServer().getScheduler().runTask(plugin, () -> { if (p.isOnline() && p.getGameMode() == to) Hotbar.clearTagged(p); });
        } else if (from == org.bukkit.GameMode.CREATIVE && p.getWorld().equals(plugin.hub().world())) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (p.isOnline() && plugin.sessions().of(p) == null && p.getGameMode() != org.bukkit.GameMode.CREATIVE) Hotbar.giveHub(p);
            });
        }
    }

    // ------------------------------------------------------------- чат по комнатам

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player sender = e.getPlayer();
        e.viewers().removeIf(a -> a instanceof Player v && !plugin.visibility().sameRoom(sender, v));
    }

    // ------------------------------------------------------------- защита хаба

    private boolean protectedHub(Player p) {
        return plugin.getConfig().getBoolean("hub.protect", true)
                && plugin.sessions().of(p) == null
                && p.getWorld().equals(plugin.hub().world());
    }

    @EventHandler(ignoreCancelled = true)
    public void onHubDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && protectedHub(p)) {
            e.setCancelled(true);
            if (e.getCause() == EntityDamageEvent.DamageCause.VOID) p.teleport(plugin.hub().location());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHubFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && protectedHub(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHubBreak(BlockBreakEvent e) {
        if (protectedHub(e.getPlayer()) && !e.getPlayer().hasPermission("mgp.admin")) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHubPlace(BlockPlaceEvent e) {
        if (protectedHub(e.getPlayer()) && !e.getPlayer().hasPermission("mgp.admin")) e.setCancelled(true);
    }
}
