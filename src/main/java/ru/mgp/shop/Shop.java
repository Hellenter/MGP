package ru.mgp.shop;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import ru.mgp.util.Cfg;
import ru.mgp.util.ItemSpec;

import java.io.File;
import java.util.*;

/**
 * Магазин в стиле Bed Wars: категории сверху, товары за валюту (железо, золото, изумруды...).
 * Хранится в plugins/MGP/shops/<id>.yml. Продавцы ставятся на карты (maps/*.yml → traders, поле shop).
 */
public final class Shop {
    /** Товар: что получает игрок и сколько стоит (материал + количество). */
    public static final class Product {
        public ItemStack item;
        public Material currency;
        public int price;

        public Product(ItemStack item, Material currency, int price) {
            this.item = item;
            this.currency = currency;
            this.price = price;
        }
    }

    public static final class Category {
        public String name;
        public Material icon;
        public final List<Product> products = new ArrayList<>();

        public Category(String name, Material icon) {
            this.name = name;
            this.icon = icon;
        }
    }

    public final String id;
    public final File file;
    public String title;
    public String npcName;
    public String profession;
    public final List<Category> categories = new ArrayList<>();

    Shop(String id, File file) {
        this.id = id;
        this.file = file;
        this.title = "<dark_gray>Магазин";
        this.npcName = "<yellow><bold>МАГАЗИН";
        this.profession = "weaponsmith";
    }

    static Shop load(String id, File f, java.util.logging.Logger log) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        Shop s = new Shop(id, f);
        s.title = y.getString("title", s.title);
        s.npcName = y.getString("npc-name", s.npcName);
        s.profession = y.getString("profession", s.profession);
        for (Map<?, ?> rawCat : y.getMapList("categories")) {
            Map<String, Object> cm = Cfg.asMap(rawCat);
            Category c = new Category(Cfg.str(cm, "name", "Категория"), Cfg.material(Cfg.str(cm, "icon", "CHEST"), Material.CHEST));
            if (cm.get("items") instanceof List<?> items) {
                for (Object o : items) {
                    Map<String, Object> pm = Cfg.asMap(o);
                    if (pm == null) continue;
                    try {
                        Object it = pm.get("item");
                        ItemStack stack = it instanceof ItemStack is ? is : ItemSpec.parse(it).build();
                        String[] price = Cfg.str(pm, "price", "IRON_INGOT 1").trim().split("\\s+");
                        Material cur = Cfg.material(price[0], Material.IRON_INGOT);
                        int amount = price.length > 1 ? Integer.parseInt(price[1]) : 1;
                        c.products.add(new Product(stack, cur, Math.max(0, amount)));
                    } catch (Exception e) {
                        log.warning("Магазин " + id + ": пропущен товар " + o + " (" + e.getMessage() + ")");
                    }
                }
            }
            s.categories.add(c);
        }
        return s;
    }

    void save() throws java.io.IOException {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of(
                "Магазин MGP. Удобнее всего править в игре: /mgp shop " + id,
                "item — предмет (как в китах: \"WHITE_WOOL 16\", {material: ..., enchants: ...} или сохранённый из игры),",
                "price — \"<валюта> <количество>\", например \"IRON_INGOT 4\""));
        y.set("title", title);
        y.set("npc-name", npcName);
        y.set("profession", profession);
        List<Map<String, Object>> cats = new ArrayList<>();
        for (Category c : categories) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("name", c.name);
            cm.put("icon", c.icon.name());
            List<Map<String, Object>> items = new ArrayList<>();
            for (Product p : c.products) {
                Map<String, Object> pm = new LinkedHashMap<>();
                // простой предмет пишем строкой (удобно править руками), сложный — целиком
                ItemStack plain = new ItemStack(p.item.getType(), p.item.getAmount());
                pm.put("item", plain.isSimilar(p.item) ? p.item.getType().name() + " " + p.item.getAmount() : p.item);
                pm.put("price", p.currency.name() + " " + p.price);
                items.add(pm);
            }
            cm.put("items", items);
            cats.add(cm);
        }
        y.set("categories", cats);
        y.save(file);
    }

    public static String currencyName(Material m, int amount) {
        return switch (m) {
            case IRON_INGOT -> amount + " железа";
            case GOLD_INGOT -> amount + " золота";
            case DIAMOND -> amount + " " + plural(amount, "алмаз", "алмаза", "алмазов");
            case EMERALD -> amount + " " + plural(amount, "изумруд", "изумруда", "изумрудов");
            default -> amount + " × " + pretty(m);
        };
    }

    public static String currencyColor(Material m) {
        return switch (m) {
            case IRON_INGOT -> "<white>";
            case GOLD_INGOT -> "<gold>";
            case DIAMOND -> "<aqua>";
            case EMERALD -> "<dark_green>";
            default -> "<yellow>";
        };
    }

    static String plural(int n, String one, String few, String many) {
        int a = Math.abs(n) % 100, b = a % 10;
        if (a > 10 && a < 20) return many;
        if (b == 1) return one;
        if (b >= 2 && b <= 4) return few;
        return many;
    }

    public static String pretty(Material m) {
        String s = m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
