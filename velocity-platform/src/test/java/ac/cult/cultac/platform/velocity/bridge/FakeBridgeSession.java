package ac.cult.cultac.platform.velocity.bridge;

import ac.cult.cultac.bridge.protocol.BridgeMessage;
import ac.cult.cultac.bridge.protocol.ServerCapability;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;
import org.jetbrains.annotations.Nullable;

/** A bridge session that records what the proxy sends. */
public final class FakeBridgeSession implements BridgeSession {
    public final List<BridgeMessage> sent = new ArrayList<>();
    public final List<CompletableFuture<Boolean>> requests = new ArrayList<>();
    public final List<CompletableFuture<@Nullable BridgeMessage>> exchanges = new ArrayList<>();
    public final Set<ServerCapability> capabilities = EnumSet.noneOf(ServerCapability.class);
    public final UUID playerId;
    public final String serverName;
    public State state = State.READY;
    public int listeners;
    public final List<Boolean> admitRequests = new ArrayList<>();
    public int admitted = Integer.MAX_VALUE;
    private int nextRequestId = 1;

    public FakeBridgeSession(UUID playerId, String serverName) {
        this(playerId, serverName, EnumSet.allOf(ServerCapability.class), 0);
    }

    public FakeBridgeSession(UUID playerId, String serverName, Set<ServerCapability> capabilities, int listeners) {
        this.playerId = playerId;
        this.serverName = serverName;
        this.capabilities.addAll(capabilities);
        this.listeners = listeners;
    }

    public <T extends BridgeMessage> T first(Class<T> type) {
        return sent.stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElseThrow();
    }

    @Override
    public State state() {
        return state;
    }

    @Override
    public boolean has(ServerCapability capability) {
        return isReady() && capabilities.contains(capability);
    }

    @Override
    public int listeners() {
        return listeners;
    }

    @Override
    public UUID playerId() {
        return playerId;
    }

    @Override
    public String serverName() {
        return serverName;
    }

    @Override
    public void send(BridgeMessage message) {
        sent.add(message);
    }

    @Override
    public CompletableFuture<Boolean> request(IntFunction<BridgeMessage> withRequestId, long timeoutMillis) {
        send(withRequestId.apply(nextRequestId++));
        var future = new CompletableFuture<Boolean>();
        requests.add(future);
        return future;
    }

    @Override
    public CompletableFuture<@Nullable BridgeMessage> exchange(
            IntFunction<BridgeMessage> withRequestId, long timeoutMillis) {
        send(withRequestId.apply(nextRequestId++));
        var future = new CompletableFuture<@Nullable BridgeMessage>();
        exchanges.add(future);
        return future;
    }

    @Override
    public boolean admitControl(boolean console) {
        admitRequests.add(console);
        if (admitted == 0) return false;
        admitted--;
        return true;
    }

    @Override
    public @Nullable UUID worldUid() {
        return null;
    }

    @Override
    public @Nullable String worldName() {
        return null;
    }
}
