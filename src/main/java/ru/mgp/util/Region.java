package ru.mgp.util;

import org.bukkit.Location;

/** Прямоугольная область по блочным координатам (включительно). */
public record Region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public static Region of(Pos a, Pos b) {
        return new Region(Math.min(a.bx(), b.bx()), Math.min(a.by(), b.by()), Math.min(a.bz(), b.bz()),
                Math.max(a.bx(), b.bx()), Math.max(a.by(), b.by()), Math.max(a.bz(), b.bz()));
    }

    public boolean contains(Location l) {
        return contains(l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** Насколько точка снаружи области по горизонтали (0 — над/под ней или внутри), с запасом margin блоков. */
    public double outsideXZ(Location l, int margin) {
        double dx = Math.max(Math.max(minX - margin - l.getX(), l.getX() - (maxX + 1 + margin)), 0);
        double dz = Math.max(Math.max(minZ - margin - l.getZ(), l.getZ() - (maxZ + 1 + margin)), 0);
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Проверка с запасом по краям (для удержания игрока на участке). */
    public boolean containsLoose(Location l, int margin) {
        return l.getBlockX() >= minX - margin && l.getBlockX() <= maxX + margin
                && l.getBlockY() >= minY - margin && l.getBlockY() <= maxY + margin
                && l.getBlockZ() >= minZ - margin && l.getBlockZ() <= maxZ + margin;
    }

    public Pos min() { return new Pos(minX, minY, minZ, 0, 0); }
    public Pos max() { return new Pos(maxX, maxY, maxZ, 0, 0); }
}
