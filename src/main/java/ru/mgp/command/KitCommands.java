package ru.mgp.command;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import ru.mgp.MGPPlugin;
import ru.mgp.config.Kit;
import ru.mgp.config.ModeConfig;
import ru.mgp.util.ItemSpec;
import ru.mgp.util.Msg;

import java.io.IOException;
import java.util.*;

/**
 * /mgp kit — наборы режимов из инвентаря:
 *   save <режим> <id> [команда|all] [название...] — «скачать инвентарь»: все слоты, надетая броня и вторая рука;
 *   load <режим> <id> — получить набор себе (чтобы поправить и сохранить снова);
 *   list <режим>, delete <режим> <id>.
 */
public final class KitCommands {
    private KitCommands() {}

    public static final List<String> SUBS = List.of("save", "load", "list", "delete");

    public static void handle(MGPPlugin plugin, Player p, String[] a) {
        if (a.length < 3) { help(p); return; }
        String sub = a[1].toLowerCase(Locale.ROOT);
        ModeConfig m = plugin.registry().mode(a[2]);
        if (m == null) { Msg.send(p, "<red>Нет режима " + Msg.esc(a[2])); return; }
        switch (sub) {
            case "list" -> {
                if (m.kits.isEmpty()) { Msg.send(p, "<gray>У режима " + m.id + " нет наборов. Собери инвентарь и: /mgp kit save " + m.id + " <id>"); return; }
                Msg.send(p, "<gold>Наборы " + m.id + " (" + m.kits.size() + "):");
                for (Kit k : m.kits.values()) {
                    p.sendMessage(Msg.parse("<gray>• <white>" + k.id() + "</white> " + k.display() + "<reset> <dark_gray>(" + k.items().size() + " предм."
                            + (k.team() != null ? ", команда " + Msg.esc(k.team()) : "") + ")</dark_gray> "
                            + "<aqua><click:run_command:'/mgp kit load " + m.id + " " + k.id() + "'><hover:show_text:'Получить себе'>[взять]</hover></click></aqua> "
                            + "<red><click:suggest_command:'/mgp kit delete " + m.id + " " + k.id() + "'><hover:show_text:'Удалить'>[x]</hover></click></red>"));
                }
            }
            case "save" -> {
                if (a.length < 4) { help(p); return; }
                String id = a[3].toLowerCase(Locale.ROOT);
                if (!id.matches("[a-z0-9_-]{1,32}")) { Msg.send(p, "<red>id набора: латиница, цифры, _ и -"); return; }
                String team = a.length > 4 ? a[4] : null;
                String display = a.length > 5 ? String.join(" ", Arrays.copyOfRange(a, 5, a.length)) : null;
                List<Map<String, Object>> items = snapshot(p.getInventory());
                if (items.isEmpty()) { Msg.send(p, "<red>Инвентарь пуст — нечего сохранять."); return; }
                YamlConfiguration y = YamlConfiguration.loadConfiguration(m.file);
                String path = "kits." + id;
                boolean existed = y.isConfigurationSection(path);
                ConfigurationSection k = existed ? y.getConfigurationSection(path) : y.createSection(path);
                if (display != null) k.set("display", display);
                else if (!k.contains("display")) k.set("display", "<yellow>" + id);
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (!k.contains("icon") || display != null) k.set("icon", (hand.getType().isAir() ? firstItem(p.getInventory()) : hand.getType()).name());
                if (team != null) k.set("team", team.equalsIgnoreCase("all") ? null : team.toLowerCase(Locale.ROOT));
                k.set("items", items);
                if (!save(p, y, m)) return;
                Kit kit = plugin.registry().reloadMode(m.id).kits.get(id);
                Msg.send(p, "<green>Набор <white>" + id + "</white> " + (existed ? "обновлён" : "сохранён") + " в режиме " + m.id + ": " + items.size()
                        + " предм., слоты и броня как сейчас" + (kit != null && kit.team() != null ? ", для команды " + kit.team() : "")
                        + ". <gray>Новые игры получат его сразу.");
            }
            case "load" -> {
                if (a.length < 4) { help(p); return; }
                Kit k = m.kits.get(a[3].toLowerCase(Locale.ROOT));
                if (k == null) { Msg.send(p, "<red>Нет набора " + Msg.esc(a[3]) + " в режиме " + m.id); return; }
                PlayerInventory inv = p.getInventory();
                inv.clear();
                for (ItemSpec s : k.items()) put(inv, s);
                Msg.send(p, "<green>Набор " + k.id() + " у тебя (инвентарь заменён). Поправь и сохрани: /mgp kit save " + m.id + " " + k.id());
            }
            case "delete" -> {
                if (a.length < 4) { help(p); return; }
                String id = a[3].toLowerCase(Locale.ROOT);
                YamlConfiguration y = YamlConfiguration.loadConfiguration(m.file);
                if (!y.contains("kits." + id)) { Msg.send(p, "<red>Нет набора " + Msg.esc(id) + " в режиме " + m.id); return; }
                y.set("kits." + id, null);
                if (!save(p, y, m)) return;
                plugin.registry().reloadMode(m.id);
                Msg.send(p, "<green>Набор " + id + " удалён из режима " + m.id);
            }
            default -> help(p);
        }
    }

    private static void help(Player p) {
        Msg.send(p, "<gold>/mgp kit save <режим> <id> [команда|all] [название]</gold> — сохранить инвентарь как набор (слоты, броня, вторая рука)");
        Msg.send(p, "<gray>   команда — например defenders или attackers в осаде: набор только для неё и вместо её стандартных предметов");
        Msg.send(p, "<gold>/mgp kit load <режим> <id></gold> — получить набор себе; <gold>/mgp kit list <режим></gold>; <gold>/mgp kit delete <режим> <id></gold>");
    }

    /** Весь инвентарь со слотами: 0-35, броня (head/chest/legs/feet) и вторая рука. */
    static List<Map<String, Object>> snapshot(PlayerInventory inv) {
        List<Map<String, Object>> out = new ArrayList<>();
        ItemStack[] storage = inv.getStorageContents();
        for (int i = 0; i < storage.length; i++) add(out, storage[i], String.valueOf(i));
        add(out, inv.getHelmet(), "head");
        add(out, inv.getChestplate(), "chest");
        add(out, inv.getLeggings(), "legs");
        add(out, inv.getBoots(), "feet");
        add(out, inv.getItemInOffHand(), "offhand");
        return out;
    }

    private static void add(List<Map<String, Object>> out, ItemStack it, String slot) {
        if (it != null && !it.getType().isAir()) out.add(ItemSpec.toMap(it, slot));
    }

    private static Material firstItem(PlayerInventory inv) {
        for (ItemStack it : inv.getContents()) if (it != null && !it.getType().isAir()) return it.getType();
        return Material.CHEST;
    }

    private static void put(PlayerInventory inv, ItemSpec s) {
        ItemStack it = s.build();
        String slot = s.slot == null ? "" : s.slot.toLowerCase(Locale.ROOT);
        switch (slot) {
            case "head", "helmet" -> inv.setHelmet(it);
            case "chest", "chestplate" -> inv.setChestplate(it);
            case "legs", "leggings" -> inv.setLeggings(it);
            case "feet", "boots" -> inv.setBoots(it);
            case "offhand" -> inv.setItemInOffHand(it);
            default -> {
                try { inv.setItem(Integer.parseInt(slot), it); } catch (NumberFormatException e) { inv.addItem(it); }
            }
        }
    }

    private static boolean save(Player p, YamlConfiguration y, ModeConfig m) {
        try {
            y.save(m.file);
            return true;
        } catch (IOException e) {
            Msg.send(p, "<red>Не удалось сохранить " + m.file.getName() + ": " + e.getMessage());
            return false;
        }
    }
}
