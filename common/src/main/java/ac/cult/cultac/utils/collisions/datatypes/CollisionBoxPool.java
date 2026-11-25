package ac.cult.cultac.utils.collisions.datatypes;

import ac.cult.blocksim.engine.shapes.VoxelShape;
import io.netty.util.concurrent.FastThreadLocal;
import java.lang.ref.WeakReference;
import java.util.Arrays;

/**
 * One thread's reusable block colliders. A query marks the pool, takes colliders and releases its mark when it returns;
 * a collider taken after a mark must not be read once that mark is released.
 */
public final class CollisionBoxPool {
    private static final int RETAINED = 256;

    // Host-owned threads outlive a runtime generation, so their map holds only host-loaded types.
    private static final FastThreadLocal<WeakReference<CollisionBoxPool>> POOLS = new FastThreadLocal<>();

    private VoxelShapeCollisionBox[] voxels = new VoxelShapeCollisionBox[16];
    private int used;
    private SimpleCollisionBox[] boxes = new SimpleCollisionBox[4];
    private int boxesUsed;

    private CollisionBoxPool() {}

    public static CollisionBoxPool current() {
        WeakReference<CollisionBoxPool> reference = POOLS.get();
        CollisionBoxPool pool = reference == null ? null : reference.get();
        if (pool == null) {
            pool = new CollisionBoxPool();
            POOLS.set(new WeakReference<>(pool));
        }
        return pool;
    }

    public int mark() {
        return used;
    }

    public void release(int mark) {
        used = mark;
    }

    /** A collider equal to {@code new VoxelShapeCollisionBox(shape, fullBlock)}, valid until the mark is released. */
    public VoxelShapeCollisionBox voxel(VoxelShape shape, boolean fullBlock) {
        if (used == voxels.length && used < RETAINED) voxels = Arrays.copyOf(voxels, Math.min(RETAINED, used * 2));
        if (used >= voxels.length) {
            used++;
            return new VoxelShapeCollisionBox(shape, fullBlock);
        }
        VoxelShapeCollisionBox box = voxels[used];
        if (box == null) {
            box = new VoxelShapeCollisionBox(shape, fullBlock);
            voxels[used] = box;
        } else {
            box.reset(shape, fullBlock);
        }
        used++;
        return box;
    }

    public int markBoxes() {
        return boxesUsed;
    }

    public void releaseBoxes(int mark) {
        boxesUsed = mark;
    }

    /** A box equal to {@code new SimpleCollisionBox()}, valid until the box mark is released. */
    public SimpleCollisionBox box() {
        int slot = takeBox();
        if (slot < 0) return new SimpleCollisionBox();
        SimpleCollisionBox box = boxes[slot];
        if (box == null) return boxes[slot] = new SimpleCollisionBox();
        return box.set(0, 0, 0, 0, 0, 0, false);
    }

    /** A box equal to {@code from.copy()}, valid until the box mark is released. */
    public SimpleCollisionBox copyOf(SimpleCollisionBox from) {
        if (from.getClass() != SimpleCollisionBox.class) return from.copy();
        int slot = takeBox();
        if (slot < 0) return from.copy();
        SimpleCollisionBox box = boxes[slot];
        if (box == null) return boxes[slot] = from.copy();
        return box.set(from);
    }

    /** Takes the next box slot: its index, or -1 past what the pool retains. */
    private int takeBox() {
        if (boxesUsed == boxes.length && boxesUsed < RETAINED)
            boxes = Arrays.copyOf(boxes, Math.min(RETAINED, boxesUsed * 2));
        int slot = boxesUsed++;
        return slot < boxes.length ? slot : -1;
    }
}
