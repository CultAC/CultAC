package ac.cult.cultac.platform.velocity.bridge;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** Bridge sessions by player. */
public interface BridgeSessions {
    BridgeSessions NONE = new BridgeSessions() {
        @Override
        public @Nullable BridgeSession current(UUID player) {
            return null;
        }

        @Override
        public void addListener(BridgeMessageListener listener) {}

        @Override
        public void removeListener(BridgeMessageListener listener) {}
    };

    /** The READY or HANDSHAKE session for the player's current backend. */
    @Nullable
    BridgeSession current(UUID player);

    void addListener(BridgeMessageListener listener);

    void removeListener(BridgeMessageListener listener);
}
