package ac.cult.cultac.bridge.protocol;

/** A frame that cannot be decoded; {@link #unknownType()} marks frames that are well-formed but not understood. */
public final class BridgeProtocolException extends RuntimeException {
    private static final int NOT_UNKNOWN = -1;

    private final int unknownType;

    public BridgeProtocolException(String message) {
        this(message, NOT_UNKNOWN);
    }

    private BridgeProtocolException(String message, int unknownType) {
        super(message, null, false, false);
        this.unknownType = unknownType;
    }

    public static BridgeProtocolException unknown(int type) {
        return new BridgeProtocolException("Unknown bridge message type " + type, type);
    }

    public boolean isUnknownType() {
        return unknownType != NOT_UNKNOWN;
    }

    /** The unrecognised type id, or -1 when the frame was malformed instead. */
    public int unknownType() {
        return unknownType;
    }
}
