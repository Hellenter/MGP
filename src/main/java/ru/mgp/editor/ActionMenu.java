package ru.mgp.editor;

import net.kyori.adventure.key.Key;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import ru.mgp.ui.Menu;
import ru.mgp.util.Items;
import ru.mgp.util.Msg;
import ru.mgp.util.Pos;

import java.util.*;
import java.util.function.BiConsumer;

/** Выбор нового действия для скрипта (как меню действий в Housing на Hypixel). */
final class ActionMenu extends Menu {
    static final String TELEPORT_HINT = "<aqua>ЛКМ палочкой по блоку</aqua> <gray>— игрок встанет <white>на</white> него и будет смотреть, куда смотришь ты. <aqua>ПКМ</aqua> <gray>— точка, где стоишь ты.";

    record Choice(Material icon, String name, String value) {}

    static final List<Choice> SOUNDS = List.of(
            new Choice(Material.ENDER_PEARL, "Телепорт (эндермен)", "minecraft:entity.enderman.teleport"),
            new Choice(Material.OBSIDIAN, "Портал", "minecraft:block.portal.trigger"),
            new Choice(Material.BELL, "Колокол", "minecraft:block.bell.use"),
            new Choice(Material.NOTE_BLOCK, "Нота: колокольчик", "minecraft:block.note_block.bell"),
            new Choice(Material.NOTE_BLOCK, "Нота: плинг", "minecraft:block.note_block.pling"),
            new Choice(Material.AMETHYST_SHARD, "Аметист", "minecraft:block.amethyst_block.chime"),
            new Choice(Material.EXPERIENCE_BOTTLE, "Опыт", "minecraft:entity.experience_orb.pickup"),
            new Choice(Material.EMERALD, "Новый уровень", "minecraft:entity.player.levelup"),
            new Choice(Material.BEACON, "Маяк включился", "minecraft:block.beacon.activate"),
            new Choice(Material.RESPAWN_ANCHOR, "Якорь возрождения", "minecraft:block.respawn_anchor.charge"),
            new Choice(Material.CHEST, "Сундук открылся", "minecraft:block.chest.open"),
            new Choice(Material.IRON_DOOR, "Железная дверь", "minecraft:block.iron_door.open"),
            new Choice(Material.PISTON, "Поршень", "minecraft:block.piston.extend"),
            new Choice(Material.STONE_BUTTON, "Щелчок", "minecraft:ui.button.click"),
            new Choice(Material.ANVIL, "Наковальня", "minecraft:block.anvil.land"),
            new Choice(Material.VILLAGER_SPAWN_EGG, "Житель: да", "minecraft:entity.villager.yes"),
            new Choice(Material.ZOMBIE_VILLAGER_SPAWN_EGG, "Житель: нет", "minecraft:entity.villager.no"),
            new Choice(Material.FIREWORK_ROCKET, "Салют", "minecraft:entity.firework_rocket.blast"),
            new Choice(Material.TNT, "Взрыв", "minecraft:entity.generic.explode"),
            new Choice(Material.LIGHTNING_ROD, "Гром", "minecraft:entity.lightning_bolt.thunder"),
            new Choice(Material.DRAGON_HEAD, "Рык дракона", "minecraft:entity.ender_dragon.growl"),
            new Choice(Material.WITHER_SKELETON_SKULL, "Иссушитель", "minecraft:entity.wither.spawn"),
            new Choice(Material.GOAT_HORN, "Рог рейда", "minecraft:event.raid.horn"),
            new Choice(Material.SCULK, "Пещера (жуть)", "minecraft:ambient.cave"));

    static final List<Choice> EFFECTS = List.of(
            new Choice(Material.SUGAR, "Скорость", "speed"),
            new Choice(Material.RABBIT_FOOT, "Прыгучесть", "jump_boost"),
            new Choice(Material.FEATHER, "Плавное падение", "slow_falling"),
            new Choice(Material.PHANTOM_MEMBRANE, "Левитация", "levitation"),
            new Choice(Material.GOLDEN_CARROT, "Ночное зрение", "night_vision"),
            new Choice(Material.FERMENTED_SPIDER_EYE, "Невидимость", "invisibility"),
            new Choice(Material.GHAST_TEAR, "Регенерация", "regeneration"),
            new Choice(Material.IRON_CHESTPLATE, "Сопротивление", "resistance"),
            new Choice(Material.SPECTRAL_ARROW, "Свечение", "glowing"),
            new Choice(Material.SOUL_SAND, "Медлительность", "slowness"),
            new Choice(Material.INK_SAC, "Слепота", "blindness"),
            new Choice(Material.SCULK_SHRIEKER, "Тьма", "darkness"),
            new Choice(Material.SPIDER_EYE, "Тошнота", "nausea"),
            new Choice(Material.MAGMA_CREAM, "Огнестойкость", "fire_resistance"));

    static final List<Choice> PARTICLES = List.of(
            new Choice(Material.OBSIDIAN, "Портал", "PORTAL"),
            new Choice(Material.CRYING_OBSIDIAN, "Обратный портал", "REVERSE_PORTAL"),
            new Choice(Material.END_ROD, "Искры края", "END_ROD"),
            new Choice(Material.FIREWORK_STAR, "Салют", "FIREWORK"),
            new Choice(Material.TOTEM_OF_UNDYING, "Тотем", "TOTEM_OF_UNDYING"),
            new Choice(Material.BLAZE_POWDER, "Пламя", "FLAME"),
            new Choice(Material.SOUL_TORCH, "Пламя душ", "SOUL_FIRE_FLAME"),
            new Choice(Material.ENCHANTING_TABLE, "Чары", "ENCHANT"),
            new Choice(Material.EMERALD, "Зелёные искры", "HAPPY_VILLAGER"),
            new Choice(Material.POPPY, "Сердечки", "HEART"),
            new Choice(Material.NOTE_BLOCK, "Ноты", "NOTE"),
            new Choice(Material.CHERRY_LEAVES, "Лепестки", "CHERRY_LEAVES"),
            new Choice(Material.CAMPFIRE, "Дым", "CAMPFIRE_COSY_SMOKE"),
            new Choice(Material.TNT, "Взрыв", "EXPLOSION"),
            new Choice(Material.LIGHTNING_ROD, "Электричество", "ELECTRIC_SPARK"),
            new Choice(Material.SNOWBALL, "Снежинки", "SNOWFLAKE"));

    private final ScriptWand wand;
    private final String mapId, id;

    ActionMenu(ScriptWand wand, Player viewer, String mapId, String id, int unused) {
        super(4, "<dark_gray>Добавить действие");
        this.wand = wand;
        this.mapId = mapId;
        this.id = id;

        add(10, Material.ENDER_PEARL, "<light_purple><bold>Телепорт", List.of("<gray>Кликни палочкой по блоку,", "<gray>на который встанет игрок"), p ->
                wand.pick(p, ScriptWand.PickKind.TELEPORT, TELEPORT_HINT, at -> addAction(p, "teleport: " + pos(at))));
        add(11, Material.PAPER, "<white><bold>Сообщение в чат", List.of("<gray>Только этому игроку"), p ->
                wand.ask(p, "<aqua>Текст сообщения:", t -> addAction(p, "message: " + t)));
        add(12, Material.NAME_TAG, "<white><bold>Заголовок на экране", List.of("<gray>Крупный текст по центру"), p ->
                wand.ask(p, "<aqua>Заголовок. Подзаголовок — после | <gray>например: <white><gold>Замок | <gray>Добро пожаловать", t -> addAction(p, "title: " + t)));
        add(13, Material.OAK_SIGN, "<white><bold>Надпись над хотбаром", List.of(), p ->
                wand.ask(p, "<aqua>Текст над хотбаром:", t -> addAction(p, "actionbar: " + t)));
        add(14, Material.NOTE_BLOCK, "<aqua><bold>Звук", List.of("<gray>Выбрать из списка"), p ->
                choose(p, "<dark_gray>Звук: ЛКМ — выбрать, ПКМ — послушать", SOUNDS, (pl, c) -> {
                    if (c.choice().value() == null) return;
                    if (c.click().isRightClick()) {
                        pl.playSound(net.kyori.adventure.sound.Sound.sound(Key.key(c.choice().value()), net.kyori.adventure.sound.Sound.Source.MASTER, 1f, 1f));
                        return;
                    }
                    addAction(pl, "sound: " + c.choice().value());
                }));
        add(15, Material.POTION, "<green><bold>Эффект", List.of("<gray>ЛКМ — 10 сек, ПКМ — 30 сек,", "<gray>Shift — навсегда (до конца игры)"), p ->
                choose(p, "<dark_gray>Эффект: ЛКМ 10с, ПКМ 30с, Shift — навсегда", EFFECTS, (pl, c) -> {
                    String dur = c.click().isShiftClick() ? "" : c.click().isRightClick() ? " 30" : " 10";
                    addAction(pl, "effect: " + c.choice().value() + " 0" + dur); // 0 — первый уровень
                }));
        add(16, Material.BLAZE_POWDER, "<aqua><bold>Частицы", List.of("<gray>Вокруг игрока"), p ->
                choose(p, "<dark_gray>Частицы вокруг игрока", PARTICLES, (pl, c) -> addAction(pl, "particle: " + c.choice().value() + " 30")));

        add(19, Material.CHEST, "<gold><bold>Выдать предмет", List.of("<gray>Возьми предмет в <white>левую руку</white>", "<gray>(F) и нажми сюда"), p -> {
            ItemStack off = p.getInventory().getItemInOffHand();
            if (off.getType().isAir()) { Msg.send(p, "<red>Положи предмет во вторую руку (клавиша F) и нажми ещё раз."); return; }
            addAction(p, "give: " + off.getType().name() + " " + off.getAmount());
        });
        add(20, Material.GRASS_BLOCK, "<yellow><bold>Поставить / убрать блок", List.of("<gray>Блок из <white>левой руки</white> (F),", "<gray>пустая рука — убрать блок (воздух).", "<gray>Потом кликни палочкой, куда ставить."), p -> {
            ItemStack off = p.getInventory().getItemInOffHand();
            Material m = off.getType().isAir() ? Material.AIR : off.getType();
            if (!m.isBlock()) { Msg.send(p, "<red>Во второй руке не блок."); return; }
            wand.pick(p, ScriptWand.PickKind.BLOCK, "<aqua>ЛКМ палочкой по месту, где будет <white>" + (m.isAir() ? "пусто" : m.name().toLowerCase(Locale.ROOT)),
                    at -> addAction(p, "setblock: " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ() + " " + m.name()));
        });
        add(21, Material.GOLDEN_APPLE, "<red><bold>Вылечить", List.of("<gray>Полное здоровье и сытость"), p -> addAction(p, "heal"));
        add(22, Material.MILK_BUCKET, "<green><bold>Снять все эффекты", List.of(), p -> addAction(p, "clear-effects"));
        add(23, Material.CLOCK, "<gray><bold>Пауза", List.of("<gray>Следующие действия выполнятся", "<gray>через N секунд"), p ->
                wand.ask(p, "<aqua>Сколько секунд ждать? <gray>например 1.5", t -> {
                    try {
                        double s = Double.parseDouble(t.replace(',', '.'));
                        addAction(p, "wait: " + ScriptWand.fmt(Math.max(0.05, s)));
                    } catch (NumberFormatException ex) {
                        Msg.send(p, "<red>Нужно число");
                        wand.reopenEditor(p);
                    }
                }));
        add(24, Material.COMMAND_BLOCK, "<dark_aqua><bold>Консольная команда", List.of("<gray>{player} — имя игрока", "<gray>например: give {player} diamond 1"), p ->
                wand.ask(p, "<aqua>Команда без / <gray>({player} — имя игрока):", t -> addAction(p, "command: " + (t.startsWith("/") ? t.substring(1) : t))));
        add(25, Material.WRITABLE_BOOK, "<white><bold>Своё действие", List.of("<gray>Любая строка, как в файле карты:", "<gray>mode: deathmatch, time: 18000 ..."), p ->
                wand.ask(p, "<aqua>Действие целиком (тип: аргументы):", t -> addAction(p, t)));

        add(26, Material.RESPAWN_ANCHOR, "<light_purple><bold>Открыть спавны", List.of("<gray>Включает группу спавнов", "<gray>(/mgp setup teamspawn ... <группа> locked):", "<gray>игроки начнут возрождаться там"), p ->
                wand.ask(p, "<aqua>Имя группы спавнов <gray>(например stage2; «-имя» — закрыть):", t -> {
                    String g = t.trim().split("\\s+")[0];
                    addAction(p, g.startsWith("-") ? "spawns: disable " + g.substring(1) : "spawns: enable " + g);
                }));
        set(31, Items.icon(Material.ARROW, "<gray>Назад", List.of(), false), wand::reopenEditor);
    }

    private void add(int slot, Material icon, String name, List<String> lore, java.util.function.Consumer<Player> fn) {
        set(slot, Items.icon(icon, name, lore, false), fn);
    }

    private void addAction(Player p, String action) {
        boolean ok = ScriptStore.editOne(wand.plugin(), mapId, id, e -> {
            List<String> l = ScriptStore.actions(e);
            l.add(action);
            e.put("actions", l);
        });
        if (!ok) { Msg.send(p, "<red>Не удалось сохранить"); return; }
        p.sendActionBar(Msg.parse("<green>Добавлено: " + ActionInfo.title(action)));
        wand.openEditor(p, mapId, id);
    }

    static String pos(Location at) {
        Pos p = Pos.of(at);
        return p.serialize();
    }

    record Picked(Choice choice, ClickType click) {}

    private void choose(Player p, String title, List<Choice> list, BiConsumer<Player, Picked> fn) {
        Menu m = new Menu(Math.min(6, (list.size() + 9 + 8) / 9), title) {
            {
                for (int i = 0; i < list.size(); i++) {
                    Choice c = list.get(i);
                    setClick(i, Items.icon(c.icon(), "<white>" + c.name(), List.of("<dark_gray>" + c.value()), false), (pl, click) -> fn.accept(pl, new Picked(c, click)));
                }
                int back = ((list.size() + 8) / 9) * 9 + 4;
                set(back, Items.icon(Material.ARROW, "<gray>Назад", List.of(), false), pl -> new ActionMenu(wand, pl, mapId, id, -1).open(pl));
            }
        };
        m.open(p);
    }
}
