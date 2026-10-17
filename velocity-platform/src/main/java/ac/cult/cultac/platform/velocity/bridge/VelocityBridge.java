package ac.cult.cultac.platform.velocity.bridge;

import java.util.Objects;

/** Process-wide access to the installed bridge sessions. */
public final class VelocityBridge {
    private static volatile BridgeSessions sessions = BridgeSessions.NONE;

    private VelocityBridge() {}

    /** Never null; {@link BridgeSessions#NONE} until installed. */
    public static BridgeSessions sessions() {
        return sessions;
    }

    public static void install(BridgeSessions sessions) {
        VelocityBridge.sessions = Objects.requireNonNull(sessions);
    }
}
