package ru.mgp.command;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import ru.mgp.util.Items;
import ru.mgp.util.Pos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Предмет выделения, как //wand у WorldEdit: ЛКМ по блоку — pos1, ПКМ по блоку — pos2.
 * Золотой топор с меткой mgp:sel_wand — не путается ни с палочкой скриптов (/mgp wand), ни с деревянным топором WorldEdit.
 */
final class SelectionWand implements Listener {
    /** Кому и какой угол поставить. */
    interface Setter { void set(Player p, boolean first, Pos pos); }

    private final NamespacedKey key;
    private final Setter setter;
    private final Map<UUID, Long> last = new HashMap<>();

    SelectionWand(Plugin plugin, Setter setter) {
        this.key = new NamespacedKey(plugin, "sel_wand");
        this.setter = setter;
    }

    ItemStack item() {
        ItemStack it = Items.icon(Material.GOLDEN_AXE, "<gold><bold>Выделение регионов", List.of(
                "<red>ЛКМ</red> <gray>по блоку — pos1", "<blue>ПКМ</blue> <gray>по блоку — pos2",
                "<dark_gray>Дальше: /mgp setup zone, fortress, plot, wall..."), true);
        var meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    boolean isWandItem(ItemStack it) { return isWand(it); }

    private boolean isWand(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(PlayerInteractEvent e) {
        if (!isWand(e.getItem())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (!p.hasPermission("mgp.admin")) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        boolean first;
        if (e.getAction() == Action.LEFT_CLICK_BLOCK) first = true;
        else if (e.getAction() == Action.RIGHT_CLICK_BLOCK) first = false;
        else return;
        // один клик иногда приходит дважды подряд — не дублируем сообщение
        long now = System.currentTimeMillis();
        Long t = last.get(p.getUniqueId());
        String sig = (first ? "1" : "2") + b.getX() + "," + b.getY() + "," + b.getZ();
        if (t != null && now - t < 150 && sig.equals(lastSig.get(p.getUniqueId()))) return;
        last.put(p.getUniqueId(), now);
        lastSig.put(p.getUniqueId(), sig);
        setter.set(p, first, Pos.ofBlock(b.getLocation()));
    }

    private final Map<UUID, String> lastSig = new HashMap<>();

    /** В креативе ЛКМ ломает блок — с топором выделения не ломаем. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (isWand(e.getPlayer().getInventory().getItemInMainHand())) e.setCancelled(true);
    }
}
