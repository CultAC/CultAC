package ac.grim.grimac.network.protocol.teleport;

public final class RelativeFlag {
    public static final RelativeFlag X = new RelativeFlag(0x01);
    public static final RelativeFlag Y = new RelativeFlag(0x02);
    public static final RelativeFlag Z = new RelativeFlag(0x04);
    public static final RelativeFlag Y_ROT = new RelativeFlag(0x08);
    public static final RelativeFlag X_ROT = new RelativeFlag(0x10);
    public static final RelativeFlag DELTA_X = new RelativeFlag(0x20);
    public static final RelativeFlag DELTA_Y = new RelativeFlag(0x40);
    public static final RelativeFlag DELTA_Z = new RelativeFlag(0x80);
    public static final RelativeFlag ROTATE_DELTA = new RelativeFlag(0x100);

    private final int mask;

    public RelativeFlag(int mask) {
        this.mask = mask;
    }

    public int getMask() {
        return mask;
    }

    public boolean isSet(int otherMask) {
        return (mask & otherMask) == otherMask;
    }
}
