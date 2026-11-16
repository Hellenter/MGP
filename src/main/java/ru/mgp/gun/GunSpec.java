package ru.mgp.gun;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Одно оружие из guns.yml. */
public record GunSpec(String id, String name, int model, String ammo, int magazine, double reload, boolean singleReload,
                      double damage, double headshot, double fireRate, List<FireMode> modes, int burst, int pellets,
                      double spread, double aimSpread, double recoil, double range, double knockback, int zoom,
                      boolean explosive, double speed, String sound, String reloadSound, List<String> lore) {

    public static final String AMMO_NONE = "none";
    public static final String AMMO_EXPLOSIVE = "explosive-arrow";

    public enum FireMode {
        AUTO("<red>ОЧЕРЕДЬ"), SEMI("<green>ОДИНОЧНЫЙ"), BURST("<yellow>ОТСЕЧКА");
        public final String label;
        FireMode(String label) { this.label = label; }
    }

    /** Тиков между выстрелами (может быть дробным: 1200 в минуту = 1 тик). */
    public double interval() { return 1200.0 / Math.max(1.0, fireRate); }

    public boolean infiniteAmmo() { return AMMO_NONE.equalsIgnoreCase(ammo); }
    public boolean arrowAmmo() { return AMMO_EXPLOSIVE.equalsIgnoreCase(ammo); }

    public String label(FireMode m) { return m == FireMode.BURST ? m.label + " ×" + burst : m.label; }

    static GunSpec parse(String id, ConfigurationSection c) {
        List<FireMode> modes = new ArrayList<>();
        for (String s : c.getStringList("modes")) {
            try { FireMode m = FireMode.valueOf(s.trim().toUpperCase(Locale.ROOT)); if (!modes.contains(m)) modes.add(m); }
            catch (IllegalArgumentException ignored) {}
        }
        if (modes.isEmpty()) modes.add(FireMode.SEMI);
        String projectile = c.getString("projectile", "bullet");
        String ammo = String.valueOf(c.get("ammo", AMMO_NONE)).trim();
        boolean explosive = projectile.equalsIgnoreCase(AMMO_EXPLOSIVE) || projectile.equalsIgnoreCase("explosive");
        return new GunSpec(id,
                c.getString("name", "<white>" + id),
                c.getInt("model", 0),
                ammo,
                Math.max(1, c.getInt("magazine", 10)),
                Math.max(0.05, c.getDouble("reload", 2.0)),
                c.getString("reload-mode", "magazine").equalsIgnoreCase("single"),
                Math.max(0, c.getDouble("damage", 4.0)),
                Math.max(0, c.getDouble("headshot", 1.5)),
                Math.max(1, c.getDouble("fire-rate", 300)),
                List.copyOf(modes),
                Math.max(1, c.getInt("burst", 3)),
                Math.max(1, c.getInt("pellets", 1)),
                Math.max(0, c.getDouble("spread", 2.0)),
                Math.max(0, c.getDouble("aim-spread", c.getDouble("spread", 2.0) / 2)),
                c.getDouble("recoil", 1.0),
                Math.max(1, c.getDouble("range", 60)),
                Math.max(0, c.getDouble("knockback", 0.05)),
                Math.max(0, Math.min(6, c.getInt("zoom", 0))),
                explosive,
                Math.max(0.2, c.getDouble("speed", 2.0)),
                c.getString("sound", "minecraft:entity.firework_rocket.blast"),
                c.getString("reload-sound", "minecraft:block.iron_door.close"),
                c.getStringList("lore"));
    }
}
