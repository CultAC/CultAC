package ac.cult.cultac.platform.velocity.bridge;

import ac.cult.cultac.bridge.protocol.BridgeMessage;
import ac.cult.cultac.bridge.protocol.ServerCapability;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;
import org.jetbrains.annotations.Nullable;

/** The bridge to the companion on one player's current backend connection. */
public interface BridgeSession {
    enum State {
        HANDSHAKE,
        READY,
        CLOSED
    }

    State state();

    default boolean isReady() {
        return state() == State.READY;
    }

    boolean has(ServerCapability capability);

    /** {@code ListenerKind} bitset reported by the backend. */
    int listeners();

    UUID playerId();

    /** Velocity {@code RegisteredServer} name. */
    String serverName();

    /**
     * Call on the player's channel event loop; queues until the backend is in PLAY and is a no-op when CLOSED. A
     * CLEAR_ACTIVE_ITEM is never queued or hopped: off the loop or outside PLAY it is dropped, because a late clear
     * could end a later use.
     */
    void send(BridgeMessage message);

    /** Sends the request built for a fresh request id; completes false on timeout or close. */
    CompletableFuture<Boolean> request(IntFunction<BridgeMessage> withRequestId, long timeoutMillis);

    /** Sends the request built for a fresh request id; completes with its reply, or null on timeout or close. */
    CompletableFuture<@Nullable BridgeMessage> exchange(IntFunction<BridgeMessage> withRequestId, long timeoutMillis);

    /**
     * Takes one of this session's tokens for a COMMAND, SUGGEST or ALERT_TOGGLE frame from the backend; the console
     * and the players have separate budgets. False when the budget is spent and the frame must be refused. Call on the
     * session's loop, after the cheap refusals, which cost no token.
     */
    boolean admitControl(boolean console);

    @Nullable
    UUID worldUid();

    @Nullable
    String worldName();
}
