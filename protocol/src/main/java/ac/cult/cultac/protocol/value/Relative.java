package ac.cult.cultac.protocol.value;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum Relative {
    X(0),
    Y(1),
    Z(2),
    Y_ROT(3),
    X_ROT(4),
    DELTA_X(5),
    DELTA_Y(6),
    DELTA_Z(7),
    ROTATE_DELTA(8);

    private final int mask;

    Relative(int bit) {
        this.mask = 1 << bit;
    }

    public static int pack(Set<Relative> values) {
        int mask = 0;
        for (Relative relative : values) mask |= relative.mask;
        return mask;
    }

    /** Vanilla ignores bits that do not identify one of the defined flags. */
    public static Set<Relative> unpack(int mask) {
        var result = EnumSet.noneOf(Relative.class);
        for (Relative relative : values()) {
            if ((mask & relative.mask) != 0) result.add(relative);
        }
        return Collections.unmodifiableSet(result);
    }

    /** Owned, immutable and in enum order, including the empty set. */
    public static Set<Relative> copyOf(Set<Relative> values) {
        var copy = EnumSet.noneOf(Relative.class);
        copy.addAll(values);
        return Collections.unmodifiableSet(copy);
    }
}
