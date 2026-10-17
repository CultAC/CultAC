package ac.cult.cultac.companion.bridge;

import ac.cult.cultac.bridge.protocol.BridgeMessage;

/** Session callbacks; {@link #onMessage} runs on the player's netty thread. */
public interface CompanionMessageHandler {
    default void onReady(CompanionSession session) {}

    default void onMessage(CompanionSession session, BridgeMessage message) {}

    default void onClosed(CompanionSession session) {}
}
