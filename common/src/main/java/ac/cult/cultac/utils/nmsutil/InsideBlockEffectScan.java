package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.BlockFamilies;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.math.CultMath;

/** A bounded absence proof before constructing the ordered movement-effect traversal. */
public final class InsideBlockEffectScan {
    public static final int POWDER = 1, WEB = 2, BERRY = 4, HONEY = 8, BUBBLE = 16;
    private static final int STUCK = POWDER | WEB | BERRY;
    private static final int ALL = STUCK | HONEY | BUBBLE;
    private static final int MAX_CELLS = 128;
    private static final double PADDING = 2.0D * 1.0E-5F;

    private InsideBlockEffectScan() {}

    public static int effects(int state) {
        return (BlockFamilies.POWDER_SNOW.test(state) ? POWDER : 0)
                | (BlockFamilies.WEB.test(state) ? WEB : 0)
                | (BlockFamilies.SWEET_BERRY_BUSH.test(state) ? BERRY : 0)
                | (BlockFamilies.HONEY.test(state) ? HONEY : 0)
                | (BlockFamilies.BUBBLE_COLUMN.test(state) ? BUBBLE : 0);
    }

    public static boolean mayHaveEffects(
            CultPlayer player, SimpleCollisionBox from, SimpleCollisionBox to, boolean stuckOnly) {
        return mayHaveEffects(from, to, stuckOnly, player.compensatedWorld, null);
    }

    static boolean mayHaveEffects(
            SimpleCollisionBox from, SimpleCollisionBox to, boolean stuckOnly, StateLookup lookup) {
        return mayHaveEffects(from, to, stuckOnly, null, lookup);
    }

    /** Reads states from {@code lookup}, or from {@code world} when it is null. */
    private static boolean mayHaveEffects(
            SimpleCollisionBox from,
            SimpleCollisionBox to,
            boolean stuckOnly,
            CompensatedWorld world,
            StateLookup lookup) {
        if (from.minX > from.maxX
                || from.minY > from.maxY
                || from.minZ > from.maxZ
                || to.minX > to.maxX
                || to.minY > to.maxY
                || to.minZ > to.maxZ) return true;
        double dxCenter = (to.minX + to.maxX) * 0.5D - (from.minX + from.maxX) * 0.5D;
        double dyCenter = (to.minY + to.maxY) * 0.5D - (from.minY + from.maxY) * 0.5D;
        double dzCenter = (to.minZ + to.maxZ) * 0.5D - (from.minZ + from.maxZ) * 0.5D;
        // Axis-ordered segments carry the starting dimensions; the direct sweep
        // carries the target dimensions backwards. Cover both translated boxes.
        double minX =
                Math.min(Math.min(from.minX, to.minX), Math.min(from.minX + dxCenter, to.minX - dxCenter)) - PADDING;
        double minY =
                Math.min(Math.min(from.minY, to.minY), Math.min(from.minY + dyCenter, to.minY - dyCenter)) - PADDING;
        double minZ =
                Math.min(Math.min(from.minZ, to.minZ), Math.min(from.minZ + dzCenter, to.minZ - dzCenter)) - PADDING;
        double maxX =
                Math.max(Math.max(from.maxX, to.maxX), Math.max(from.maxX + dxCenter, to.maxX - dxCenter)) + PADDING;
        double maxY =
                Math.max(Math.max(from.maxY, to.maxY), Math.max(from.maxY + dyCenter, to.maxY - dyCenter)) + PADDING;
        double maxZ =
                Math.max(Math.max(from.maxZ, to.maxZ), Math.max(from.maxZ + dzCenter, to.maxZ - dzCenter)) + PADDING;
        if (!Double.isFinite(minX)
                || !Double.isFinite(minY)
                || !Double.isFinite(minZ)
                || !Double.isFinite(maxX)
                || !Double.isFinite(maxY)
                || !Double.isFinite(maxZ)
                || minX > maxX
                || minY > maxY
                || minZ > maxZ) return true;
        // Deflation can invert a very thin box. Padding covers that inversion and
        // the corner/DDA arithmetic without scanning whole neighboring blocks.
        int x0 = CultMath.floor(minX), y0 = CultMath.floor(minY), z0 = CultMath.floor(minZ);
        int x1 = CultMath.floor(maxX), y1 = CultMath.floor(maxY), z1 = CultMath.floor(maxZ);
        if (x0 == Integer.MIN_VALUE
                || y0 == Integer.MIN_VALUE
                || z0 == Integer.MIN_VALUE
                || x1 >= Integer.MAX_VALUE - 1
                || y1 >= Integer.MAX_VALUE - 1
                || z1 >= Integer.MAX_VALUE - 1) return true;
        long dx = (long) x1 - x0 + 1, dy = (long) y1 - y0 + 1, dz = (long) z1 - z0 + 1;
        if (dx > MAX_CELLS || dy > MAX_CELLS || dz > MAX_CELLS || dx * dy * dz > MAX_CELLS) return true;
        int wanted = stuckOnly ? STUCK : ALL;
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) {
                    int state = lookup == null ? world.getBlockStateIdAt(x, y, z) : lookup.get(x, y, z);
                    if ((effects(state) & wanted) != 0) return true;
                }
        return false;
    }

    @FunctionalInterface
    interface StateLookup {
        int get(int x, int y, int z);
    }
}
