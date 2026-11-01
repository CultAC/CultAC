package ac.cult.cultac.checks.impl.prediction.checks;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.nmsutil.Collisions;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * VanillaStepProof's collisions within one Java prediction, keyed on the raw bits of every argument that varies inside
 * it. A hit is exact only while the unkeyed inputs (carry, step height, sneaking, border, world) stay fixed; the
 * prediction writes profileCarry before the memo's first entry and none of them after it.
 */
public final class StepCollisionMemo {
    private static final int KEY_LONGS = 10;
    private static final long STEP = 1L;
    private static final long BELOW = 2L;
    private static final long Z_FIRST = 4L;
    private static final long ON_GROUND = 8L;
    private static final long FULL_BLOCK = 16L;

    private final long[] pending = new long[KEY_LONGS];
    private long[] keys = new long[KEY_LONGS * 32];
    private Object[] values = new Object[32];
    private int[] table = new int[64];
    private int size;
    private boolean open;

    public void open(boolean enabled) {
        clear();
        open = enabled;
    }

    public void close() {
        clear();
        open = false;
    }

    Collisions.SteppedCollision steppedCollision(
            CultPlayer player,
            SimpleCollisionBox box,
            double desiredX,
            double desiredY,
            double desiredZ,
            List<Collisions.Axis> order,
            boolean onGroundForStep) {
        if (!open) {
            return Collisions.collideWithStepComparison(
                    player, box, desiredX, desiredY, desiredZ, order, Collections.emptyList(), onGroundForStep);
        }
        long tag = STEP | (order.get(1) == Collisions.Axis.Z ? Z_FIRST : 0L) | (onGroundForStep ? ON_GROUND : 0L);
        int found = find(tag, box, desiredX, desiredY, desiredZ);
        if (found >= 0) return (Collisions.SteppedCollision) values[found];
        Collisions.SteppedCollision collision = Collisions.collideWithStepComparison(
                player, box, desiredX, desiredY, desiredZ, order, Collections.emptyList(), onGroundForStep);
        insert(-found - 1, collision);
        return collision;
    }

    boolean collidesDirectlyBelow(CultPlayer player, SimpleCollisionBox box) {
        if (!open) return collidesDirectlyBelowUncached(player, box);
        int found = find(BELOW | (player.lastOnGround ? ON_GROUND : 0L), box, 0.0D, 0.0D, 0.0D);
        if (found >= 0) return (Boolean) values[found];
        boolean below = collidesDirectlyBelowUncached(player, box);
        insert(-found - 1, below);
        return below;
    }

    static boolean collidesDirectlyBelowUncached(CultPlayer player, SimpleCollisionBox box) {
        return Collisions.collide(player, box, 0.0D, -SimpleCollisionBox.COLLISION_EPSILON, 0.0D).y
                != -SimpleCollisionBox.COLLISION_EPSILON;
    }

    private int find(long tag, SimpleCollisionBox box, double desiredX, double desiredY, double desiredZ) {
        long[] key = pending;
        key[0] = box.isFullBlock() ? tag | FULL_BLOCK : tag;
        key[1] = Double.doubleToRawLongBits(box.minX);
        key[2] = Double.doubleToRawLongBits(box.minY);
        key[3] = Double.doubleToRawLongBits(box.minZ);
        key[4] = Double.doubleToRawLongBits(box.maxX);
        key[5] = Double.doubleToRawLongBits(box.maxY);
        key[6] = Double.doubleToRawLongBits(box.maxZ);
        key[7] = Double.doubleToRawLongBits(desiredX);
        key[8] = Double.doubleToRawLongBits(desiredY);
        key[9] = Double.doubleToRawLongBits(desiredZ);
        int mask = table.length - 1;
        for (int slot = hash(key, 0) & mask; ; slot = (slot + 1) & mask) {
            int entry = table[slot] - 1;
            if (entry < 0) return -slot - 1;
            if (Arrays.equals(keys, entry * KEY_LONGS, entry * KEY_LONGS + KEY_LONGS, key, 0, KEY_LONGS)) {
                return entry;
            }
        }
    }

    private void insert(int slot, Object value) {
        if (size == values.length) {
            keys = Arrays.copyOf(keys, keys.length * 2);
            values = Arrays.copyOf(values, values.length * 2);
        }
        System.arraycopy(pending, 0, keys, size * KEY_LONGS, KEY_LONGS);
        values[size] = value;
        table[slot] = ++size;
        if (size * 2 > table.length) rehash(table.length * 2);
    }

    private void rehash(int length) {
        table = new int[length];
        int mask = length - 1;
        for (int entry = 0; entry < size; entry++) {
            int slot = hash(keys, entry * KEY_LONGS) & mask;
            while (table[slot] != 0) slot = (slot + 1) & mask;
            table[slot] = entry + 1;
        }
    }

    private void clear() {
        if (size == 0) return;
        Arrays.fill(values, 0, size, null);
        Arrays.fill(table, 0);
        size = 0;
    }

    private static int hash(long[] key, int from) {
        long h = 0L;
        for (int i = from; i < from + KEY_LONGS; i++) h = h * 0x9E3779B97F4A7C15L + key[i];
        h ^= h >>> 32;
        return (int) (h ^ (h >>> 16));
    }
}
