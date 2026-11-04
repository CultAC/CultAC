package ac.cult.cultac.companion.bridge;

import ac.cult.cultac.bridge.protocol.BridgeMessage;

/** Session callbacks; {@link #onMessage} runs on the player's netty thread. */
public interface CompanionMessageHandler {
    default void onReady(CompanionSession session) {}

    /** Runs on the main thread after READY is sent; the session accepts messages. */
    default void onOpened(CompanionSession session) {}

    default void onMessage(CompanionSession session, BridgeMessage message) {}

    default void onClosed(CompanionSession session) {}
}
