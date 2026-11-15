package ru.mgp.util;

import org.bukkit.Location;
import org.bukkit.World;

/** Координаты без привязки к миру: одна и та же карта загружается во множество копий-миров. */
public record Pos(double x, double y, double z, float yaw, float pitch) {

    public static Pos parse(String s) {
        if (s == null || s.isBlank()) return null;
        String[] p = s.trim().split("[\\s,]+");
        if (p.length < 3) throw new IllegalArgumentException("Ожидалось 'x y z [yaw pitch]': " + s);
        float yaw = p.length > 3 ? Float.parseFloat(p[3]) : 0f;
        float pitch = p.length > 4 ? Float.parseFloat(p[4]) : 0f;
        return new Pos(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]), yaw, pitch);
    }

    public static Pos of(Location l) {
        return new Pos(round(l.getX()), round(l.getY()), round(l.getZ()),
                Math.round(l.getYaw()), Math.round(l.getPitch()));
    }

    public static Pos ofBlock(Location l) {
        return new Pos(l.getBlockX(), l.getBlockY(), l.getBlockZ(), 0, 0);
    }

    private static double round(double v) { return Math.round(v * 100) / 100.0; }

    public Location toLocation(World w) { return new Location(w, x, y, z, yaw, pitch); }

    public int bx() { return (int) Math.floor(x); }
    public int by() { return (int) Math.floor(y); }
    public int bz() { return (int) Math.floor(z); }

    public String serialize() {
        if (yaw == 0 && pitch == 0) return fmt(x) + " " + fmt(y) + " " + fmt(z);
        return fmt(x) + " " + fmt(y) + " " + fmt(z) + " " + fmt(yaw) + " " + fmt(pitch);
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
