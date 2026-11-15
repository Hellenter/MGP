package ru.mgp.shop;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import ru.mgp.MGPPlugin;
import ru.mgp.session.GameSession;
import ru.mgp.util.Msg;

import java.io.File;
import java.util.*;

/** Магазины (plugins/MGP/shops/*.yml), продавцы с меткой mgp:shop и покупка. */
public final class ShopService implements Listener {
    private final MGPPlugin plugin;
    private final File dir;
    private final NamespacedKey shopKey;
    private final Map<String, Shop> shops = new LinkedHashMap<>();

    public ShopService(MGPPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "shops");
        this.shopKey = new NamespacedKey(plugin, "shop");
    }

    public MGPPlugin plugin() { return plugin; }

    public void load() {
        shops.clear();
        if (!dir.isDirectory()) {
            dir.mkdirs();
            plugin.saveResource("shops/bedwars.yml", false);
        }
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return;
        Arrays.sort(files);
        for (File f : files) {
            String id = f.getName().substring(0, f.getName().length() - 4).toLowerCase(Locale.ROOT);
            try {
                shops.put(id, Shop.load(id, f, plugin.getLogger()));
            } catch (Exception e) {
                plugin.getLogger().warning("Не удалось загрузить магазин " + f.getName() + ": " + e.getMessage());
            }
        }
    }

    public Collection<Shop> all() { return shops.values(); }
    public Shop get(String id) { return id == null ? null : shops.get(id.toLowerCase(Locale.ROOT)); }

    public Shop create(String id) {
        id = id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        if (id.isEmpty() || shops.containsKey(id)) return null;
        Shop s = new Shop(id, new File(dir, id + ".yml"));
        s.categories.add(new Shop.Category("<yellow>Блоки", Material.WHITE_WOOL));
        shops.put(id, s);
        save(s);
        return s;
    }

    public boolean save(Shop s) {
        try {
            dir.mkdirs();
            s.save();
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Не удалось сохранить магазин " + s.id + ": " + e.getMessage());
            return false;
        }
    }

    public void delete(Shop s) {
        shops.remove(s.id);
        if (!s.file.delete()) s.file.deleteOnExit();
    }

    // ------------------------------------------------------------------ продавцы

    public void tag(Entity e, String shopId) {
        e.getPersistentDataContainer().set(shopKey, PersistentDataType.STRING, shopId);
    }

    public String shopOf(Entity e) {
        return e.getPersistentDataContainer().get(shopKey, PersistentDataType.STRING);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClickNpc(PlayerInteractEntityEvent e) {
        String id = shopOf(e.getRightClicked());
        if (id == null) return;
        e.setCancelled(true);
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return; // событие приходит и для второй руки
        Player p = e.getPlayer();
        Shop shop = get(id);
        if (shop == null) { Msg.send(p, "<red>Магазин " + Msg.esc(id) + " не найден"); return; }
        GameSession s = plugin.sessions().of(p);
        boolean canBuy = s != null ? s.isPlaying() && s.isAlive(p) : p.hasPermission("mgp.admin");
        if (!canBuy) return;
        if (s == null && p.isSneaking()) { new ShopEditMenu(this, shop, 0).open(p); return; }
        new ShopMenu(this, shop, 0).open(p);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onNpcDamage(EntityDamageEvent e) {
        if (shopOf(e.getEntity()) != null) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ покупка

    static int count(Player p, Material m) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) if (it != null && it.getType() == m) n += it.getAmount();
        ItemStack off = p.getInventory().getItemInOffHand();
        if (off.getType() == m) n += off.getAmount();
        return n;
    }

    /** true — куплено. */
    boolean buy(Player p, Shop.Product pr) {
        int have = count(p, pr.currency);
        if (have < pr.price) {
            p.sendMessage(Msg.parse("<red>Не хватает: нужно ещё " + Shop.currencyColor(pr.currency) + Shop.currencyName(pr.currency, pr.price - have)));
            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 0.6f);
            return false;
        }
        if (pr.price > 0) p.getInventory().removeItemAnySlot(new ItemStack(pr.currency, pr.price));
        ItemStack give = pr.item.clone();
        // броня сразу надевается (как в Bed Wars), снятая вещь уходит в инвентарь
        org.bukkit.inventory.EquipmentSlot slot = give.getType().getEquipmentSlot();
        if (slot.isArmor() && slot != org.bukkit.inventory.EquipmentSlot.BODY && give.getAmount() == 1) {
            ItemStack old = p.getInventory().getItem(slot);
            p.getInventory().setItem(slot, give);
            give = old == null || old.getType().isAir() ? null : old;
        }
        Map<Integer, ItemStack> left = give == null ? Map.of() : p.getInventory().addItem(give);
        for (ItemStack it : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), it);
        p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 2f);
        p.sendMessage(Msg.parse("<green>Куплено: <white>" + itemName(pr.item)));
        return true;
    }

    static String itemName(ItemStack it) {
        String name = it.getItemMeta() != null && it.getItemMeta().hasDisplayName()
                ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it.getItemMeta().displayName())
                : Shop.pretty(it.getType());
        return Msg.esc(name) + (it.getAmount() > 1 ? " ×" + it.getAmount() : "");
    }
}
