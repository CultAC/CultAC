package ac.cult.cultac.protocol.value;

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

    public static int pack(java.util.Set<Relative> values) {
        int mask = 0;
        for (Relative relative : values) mask |= relative.mask;
        return mask;
    }

    /** Vanilla ignores bits that do not identify one of the defined flags. */
    public static java.util.Set<Relative> unpack(int mask) {
        var result = java.util.EnumSet.noneOf(Relative.class);
        for (Relative relative : values()) {
            if ((mask & relative.mask) != 0) result.add(relative);
        }
        return java.util.Collections.unmodifiableSet(result);
    }

    /** Owned, immutable and in enum order, including the empty set. */
    public static java.util.Set<Relative> copyOf(java.util.Set<Relative> values) {
        var copy = java.util.EnumSet.noneOf(Relative.class);
        copy.addAll(values);
        return java.util.Collections.unmodifiableSet(copy);
    }
}
