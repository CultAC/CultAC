package ac.cult.cultac.bridge.protocol;

import java.util.Set;

/** Wire constants shared by the CultAC proxy and the Paper companion. */
public final class BridgeProtocol {
    public static final int VERSION = 1;
    public static final String CHANNEL = "cultac:bridge";
    public static final int MAX_STRING_BYTES = 32767;
    public static final int MAX_FRAME_BYTES = 1 << 20;
    public static final int NONCE_BYTES = 16;
    public static final int HMAC_BYTES = 32;
    /** Command roots a backend forwards to the proxy; anything else would reach unrelated proxy commands. */
    public static final Set<String> COMMAND_ROOTS = Set.of("cult", "cultac", "grim", "grimac");

    private BridgeProtocol() {}
}
