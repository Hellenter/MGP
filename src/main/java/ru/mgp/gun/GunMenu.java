package ru.mgp.gun;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ru.mgp.ui.Menu;
import ru.mgp.util.Items;
import ru.mgp.util.Msg;

import java.util.ArrayList;
import java.util.List;

/**
 * Админ-меню выдачи оружия (/mgp gun): ЛКМ по оружию — само оружие, Shift — ещё и три магазина патронов;
 * ЛКМ по патронам — стак, Shift — четыре стака. Внизу — включить или снять ресурспак с моделями.
 */
public final class GunMenu extends Menu {
    private static final int AMMO_ROW = 36, PACK = 45, CLOSE = 53;
    private final GunService guns;
    private final Player target;

    public GunMenu(GunService guns, Player target) {
        super(6, "<dark_gray>Оружие" + "<gray> → " + Msg.esc(target.getName()));
        this.guns = guns;
        this.target = target;
        int slot = 0;
        for (GunSpec g : guns.guns().values()) {
            if (slot >= AMMO_ROW) break;
            ItemStack icon = guns.item(g.id());
            addLore(icon, "", "<yellow>ЛКМ <gray>— выдать", g.infiniteAmmo() ? null : "<yellow>Shift+ЛКМ <gray>— с тремя магазинами");
            setClick(slot++, icon, (p, c) -> give(p, g, c.isShiftClick()));
        }
        List<String> types = new ArrayList<>(guns.ammoTypes());
        types.add(GunSpec.AMMO_EXPLOSIVE);
        int a = AMMO_ROW;
        for (String t : types) {
            if (a >= PACK) break;
            ItemStack icon = guns.ammo(t, 64);
            if (icon == null) continue;
            addLore(icon, "", "<yellow>ЛКМ <gray>— стак (64)", "<yellow>Shift+ЛКМ <gray>— 4 стака");
            setClick(a++, icon, (p, c) -> ammo(p, t, c.isShiftClick() ? 256 : 64));
        }
        drawPack();
        set(CLOSE, Items.icon(Material.BARRIER, "<red>Закрыть", List.of(), false), Player::closeInventory);
    }

    private void drawPack() {
        PackService pack = guns.pack();
        boolean on = pack.manual(target);
        boolean has = pack.loaded(target);
        set(PACK, Items.icon(on ? Material.LIME_DYE : Material.GRAY_DYE,
                on ? "<green>Ресурспак включён вручную" : "<yellow>Включить ресурспак",
                List.of("<gray>Модели оружия и военные инструменты", "<gray>вне режима, для проверки.",
                        "<gray>Сейчас загружен: " + (has ? "<green>да" : "<red>нет"), "",
                        on ? "<yellow>Клик <gray>— снять" : "<yellow>Клик <gray>— загрузить"), on), p -> {
            if (pack.manual(target)) {
                if (!pack.unloadManual(target)) Msg.send(p, "<gray>Ты в режиме с паком — он снимется при выходе в хаб.");
                else Msg.send(p, "<gray>Ресурспак снят.");
            } else if (pack.loadManual(target)) Msg.send(p, "<green>Отправляю ресурспак (" + pack.packCount() + " шт.)...");
            else Msg.send(p, "<red>В guns.yml не задан ни один ресурспак.");
            drawPack();
        });
    }

    private void give(Player p, GunSpec g, boolean withAmmo) {
        ItemStack it = guns.item(g.id());
        if (it == null) return;
        put(it);
        if (withAmmo && !g.infiniteAmmo()) ammo(null, g.ammo(), g.magazine() * 3);
        Msg.send(p, "<green>Выдано: " + g.name() + (target.equals(p) ? "" : " <gray>→ " + Msg.esc(target.getName())));
    }

    private void ammo(Player p, String type, int n) {
        for (int left = n; left > 0; left -= 64) {
            ItemStack it = guns.ammo(type, Math.min(64, left));
            if (it == null) return;
            put(it);
        }
        if (p != null) Msg.send(p, "<green>Патроны " + Msg.esc(type) + ": " + n + (target.equals(p) ? "" : " <gray>→ " + Msg.esc(target.getName())));
    }

    private void put(ItemStack it) {
        target.getInventory().addItem(it).values().forEach(x -> target.getWorld().dropItem(target.getLocation(), x));
    }

    private static void addLore(ItemStack it, String... lines) {
        ItemMeta m = it.getItemMeta();
        var lore = m.lore() == null ? new ArrayList<net.kyori.adventure.text.Component>() : new ArrayList<>(m.lore());
        for (String l : lines) if (l != null) lore.add(Msg.item(l));
        m.lore(lore);
        it.setItemMeta(m);
    }
}
