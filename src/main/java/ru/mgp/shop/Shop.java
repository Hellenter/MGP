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
    /** Валюта «монеты счёта» в файле магазина: price: "MONEY 300". */
    public static final String MONEY = "MONEY";

    /**
     * Товар: что получает игрок и сколько стоит (материал + количество или монеты счёта режима).
     * Набор (kit): contents — несколько предметов за одну покупку, item — его иконка с названием.
     */
    public static final class Product {
        public ItemStack item;
        public Material currency;
        public int price;
        /** Платить монетами со счёта игрока (экономика режима, например Штурм), а не предметами. */
        public boolean money;
        /** Набор: все предметы, которые выдаются за одну покупку (null — обычный товар item). */
        public List<ItemStack> contents;
        /** Название набора (MiniMessage). */
        public String bundleName;

        public Product(ItemStack item, Material currency, int price) {
            this.item = item;
            this.currency = currency;
            this.price = price;
        }

        public boolean isBundle() { return contents != null && !contents.isEmpty(); }

        /** Что выдаётся за покупку. */
        public List<ItemStack> goods() { return isBundle() ? contents : List.of(item); }

        public String priceText(int amount) { return money ? moneyName(amount) : currencyName(currency, amount); }

        public String priceColor() { return money ? "<gold>" : currencyColor(currency); }

        /** Иконка набора: материал + название + список содержимого в описании. */
        public static ItemStack bundleIcon(Material icon, String name, List<ItemStack> contents) {
            ItemStack it = new ItemStack(icon);
            org.bukkit.inventory.meta.ItemMeta meta = it.getItemMeta();
            meta.displayName(ru.mgp.util.Msg.item(name));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(ru.mgp.util.Msg.item("<gray>Набор — всё за одну покупку:"));
            for (ItemStack c : contents) lore.add(ru.mgp.util.Msg.item("<dark_gray>• <white>" + ShopService.itemName(c)));
            meta.lore(lore);
            it.setItemMeta(meta);
            return it;
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
                        Object list = pm.get("items") instanceof List<?> l ? l : it instanceof List<?> l2 ? l2 : null;
                        String[] price = Cfg.str(pm, "price", "IRON_INGOT 1").trim().split("\\s+");
                        boolean money = isMoney(price[0]);
                        Material cur = money ? Material.GOLD_NUGGET : Cfg.material(price[0], Material.IRON_INGOT);
                        int amount = price.length > 1 ? Integer.parseInt(price[1]) : 1;
                        Product pr;
                        if (list instanceof List<?> l) {
                            // набор: несколько предметов за одну покупку
                            List<ItemStack> contents = new ArrayList<>();
                            for (Object x : l) contents.add(x instanceof ItemStack is ? is : ItemSpec.parse(x).build());
                            if (contents.isEmpty()) throw new IllegalArgumentException("пустой набор");
                            String name = Cfg.str(pm, "name", Cfg.str(pm, "kit", "<gold>Набор"));
                            Material icon = Cfg.material(Cfg.str(pm, "icon", null), contents.get(0).getType());
                            pr = new Product(Product.bundleIcon(icon, name, contents), cur, Math.max(0, amount));
                            pr.contents = contents;
                            pr.bundleName = name;
                        } else {
                            ItemStack stack = it instanceof ItemStack is ? is : ItemSpec.parse(it).build();
                            pr = new Product(stack, cur, Math.max(0, amount));
                        }
                        pr.money = money;
                        c.products.add(pr);
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
                "price — \"<валюта> <количество>\", например \"IRON_INGOT 4\"; \"MONEY 300\" — монеты счёта (Штурм)",
                "Набор (несколько предметов за одну покупку): {name: \"<gold>Штурмовик\", icon: IRON_AXE, items: [...], price: \"MONEY 500\"}"));
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
                if (p.isBundle()) {
                    pm.put("name", p.bundleName == null ? "<gold>Набор" : p.bundleName);
                    pm.put("icon", p.item.getType().name());
                    List<Object> list = new ArrayList<>();
                    for (ItemStack c2 : p.contents) list.add(plainOrFull(c2));
                    pm.put("items", list);
                } else pm.put("item", plainOrFull(p.item));
                pm.put("price", (p.money ? MONEY : p.currency.name()) + " " + p.price);
                items.add(pm);
            }
            cm.put("items", items);
            cats.add(cm);
        }
        y.set("categories", cats);
        y.save(file);
    }

    /** Простой предмет пишем строкой (удобно править руками), сложный — целиком. */
    private static Object plainOrFull(ItemStack it) {
        ItemStack plain = new ItemStack(it.getType(), it.getAmount());
        return plain.isSimilar(it) ? it.getType().name() + " " + it.getAmount() : it;
    }

    static boolean isMoney(String s) {
        String v = s.trim().toUpperCase(Locale.ROOT);
        return v.equals(MONEY) || v.equals("COINS") || v.equals("$");
    }

    public static String moneyName(int amount) {
        return amount + " " + plural(amount, "монета", "монеты", "монет");
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
