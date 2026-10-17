package ac.cult.cultac.companion.bridge;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** The backend end of the proxy bridge. */
public interface CompanionBridge {
    /** False when no secret is configured; no session ever opens then. */
    default boolean isEnabled() {
        return true;
    }

    /** The player's session when READY. */
    @Nullable
    CompanionSession session(UUID player);

    /** Any READY session, for traffic that is not tied to a player such as console commands. */
    @Nullable
    CompanionSession anySession();

    void addHandler(CompanionMessageHandler handler);

    /** Reports which Grim listener kinds exist; the bridge sends LISTENERS when the bits change. */
    void setListenerBits(int listenerKindBits);

    /** Declares capabilities a module serves; call before sessions open. */
    void addCapabilities(long capabilityMask);
}
