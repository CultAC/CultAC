package ac.cult.cultac.platform.velocity.bridge;

import ac.cult.cultac.bridge.protocol.BridgeMessage;

/** Bridge session callbacks; all run on the player's channel event loop. */
public interface BridgeMessageListener {
    default void onReady(BridgeSession session) {}

    default void onMessage(BridgeSession session, BridgeMessage message) {}

    default void onClosed(BridgeSession session) {}
}
