package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.utils.math.Vec3;
import java.util.Collection;
import java.util.HashSet;

/**
 * A velocity {@link HashSet} that can be cleared and refilled in place. While {@link #reusable()} holds its table
 * is still the default sixteen buckets, so a clear and refill of at most {@link #MAX_REFILL} elements iterates in
 * exactly the order a freshly constructed set would.
 */
public final class ReusableVelocitySet extends HashSet<Vec3> {
    /** {@code new HashSet<>(collection)} sizes larger collections past sixteen buckets. */
    private static final int MAX_REFILL = 11;

    // Eight elements stay below both the resize threshold and the nine-per-bin treeify resize.
    private static final int MAX_RETAINED = 8;

    private boolean overgrown;

    public ReusableVelocitySet() {}

    public ReusableVelocitySet(Collection<? extends Vec3> velocities) {
        super(velocities);
        if (size() > MAX_RETAINED) overgrown = true;
    }

    @Override
    public boolean add(Vec3 velocity) {
        boolean added = super.add(velocity);
        if (size() > MAX_RETAINED) overgrown = true;
        return added;
    }

    @Override
    public boolean addAll(Collection<? extends Vec3> velocities) {
        boolean added = super.addAll(velocities);
        if (size() > MAX_RETAINED) overgrown = true;
        return added;
    }

    public boolean reusable() {
        return !overgrown;
    }

    /** Replaces the contents with {@code velocities}; returns {@code false} without changes when a refill could iterate differently. */
    public boolean refill(Collection<? extends Vec3> velocities) {
        if (overgrown || velocities.size() > MAX_REFILL) return false;
        clear();
        addAll(velocities);
        return true;
    }
}
