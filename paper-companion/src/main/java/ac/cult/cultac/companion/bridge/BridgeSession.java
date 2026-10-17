package ac.cult.cultac.companion.bridge;

import ac.cult.cultac.bridge.protocol.BridgeMessage;
import java.util.UUID;
import org.bukkit.entity.Player;

/** One handshake, and once READY the live session, over one player connection. */
final class BridgeSession implements CompanionSession {
    static final int AWAITING_AUTH = 0;
    static final int AUTHENTICATED = 1;
    static final int READY = 2;
    static final int CLOSED = 3;

    final BridgeEndpoint endpoint;
    final byte[] proxyNonce;
    final byte[] backendNonce;
    private final UUID playerId;
    volatile int state = AWAITING_AUTH;
    volatile Player player;

    BridgeSession(BridgeEndpoint endpoint, UUID playerId, byte[] proxyNonce, byte[] backendNonce) {
        this.endpoint = endpoint;
        this.playerId = playerId;
        this.proxyNonce = proxyNonce;
        this.backendNonce = backendNonce;
    }

    @Override
    public UUID playerId() {
        return playerId;
    }

    @Override
    public Player player() {
        return player;
    }

    @Override
    public boolean isReady() {
        return state == READY;
    }

    @Override
    public void send(BridgeMessage message) {
        if (state == READY) endpoint.send(message);
    }

    @Override
    public void execute(Runnable task) {
        endpoint.execute(task);
    }

    @Override
    public boolean runInPacketOrder(Runnable task) {
        return endpoint.runInPacketOrder(task);
    }
}
