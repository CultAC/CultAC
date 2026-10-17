package ac.cult.cultac.platform.velocity.bridge.command;

import ac.cult.cultac.bridge.protocol.BridgeMessage.PlayerState;
import ac.cult.cultac.bridge.protocol.BridgeMessage.PlayerStateResult;
import ac.cult.cultac.bridge.protocol.BridgeMessage.SetGameMode;
import ac.cult.cultac.bridge.protocol.BridgeMessage.TeleportToPlayer;
import ac.cult.cultac.bridge.protocol.ServerCapability;
import ac.cult.cultac.manager.SpectateManager;
import ac.cult.cultac.manager.SpectateManager.ServerState;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.velocity.bridge.BackendWorld;
import ac.cult.cultac.platform.velocity.bridge.BridgeMessageListener;
import ac.cult.cultac.platform.velocity.bridge.BridgeSession;
import ac.cult.cultac.platform.velocity.bridge.BridgeSessions;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.math.Location;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;

/** Spectating across backends: connects the staff member, waits for the companion there, then acts through it. */
final class BridgeSpectateRouter implements SpectateManager.Router, BridgeMessageListener {
    static final long READY_TIMEOUT_MILLIS = 10_000;
    static final long TELEPORT_TIMEOUT_MILLIS = 5_000;
    static final long STATE_TIMEOUT_MILLIS = 5_000;
    static final long CONNECT_MARGIN_MILLIS = 30_000;

    private record Arrival(String server, CompletableFuture<Boolean> ready) {}

    private final ProxyServer proxy;
    private final BridgeSessions sessions;
    private final long readyTimeoutMillis;
    private final long connectTimeoutMillis;
    private final Map<UUID, Arrival> arrivals = new ConcurrentHashMap<>();

    BridgeSpectateRouter(
            ProxyServer proxy, BridgeSessions sessions, long readyTimeoutMillis, long connectTimeoutMillis) {
        this.proxy = proxy;
        this.sessions = sessions;
        this.readyTimeoutMillis = readyTimeoutMillis;
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    void close() {
        for (var arrival : arrivals.values()) arrival.ready().complete(false);
        arrivals.clear();
    }

    @Override
    public @Nullable String serverName(PlatformPlayer player) {
        return player.getNative() instanceof Player proxied
                ? proxied.getCurrentServer()
                        .map(server -> server.getServerInfo().getName())
                        .orElse(null)
                : null;
    }

    @Override
    public CompletableFuture<Boolean> connect(PlatformPlayer player, String server) {
        RegisteredServer target = proxy.getServer(server).orElse(null);
        if (!(player.getNative() instanceof Player proxied) || target == null) {
            return CompletableFuture.completedFuture(false);
        }
        UUID id = proxied.getUniqueId();
        var arrival = new Arrival(server, new CompletableFuture<>());
        var previous = arrivals.put(id, arrival);
        if (previous != null) previous.ready().complete(false);
        arrival.ready().whenComplete((ready, failure) -> arrivals.remove(id, arrival));
        CompletableFuture<ConnectionRequestBuilder.Result> request =
                proxied.createConnectionRequest(target).connect();
        CompletableFuture.delayedExecutor(connectTimeoutMillis, TimeUnit.MILLISECONDS)
                .execute(() -> {
                    if (!request.isDone()) arrival.ready().complete(false);
                });
        request.whenComplete((result, failure) -> {
            if (failure != null
                    || result == null
                    || !(result.isSuccessful()
                            || result.getStatus() == ConnectionRequestBuilder.Status.ALREADY_CONNECTED)) {
                arrival.ready().complete(false);
            } else {
                arrival.ready().completeOnTimeout(false, readyTimeoutMillis, TimeUnit.MILLISECONDS);
                arrived(sessions.current(id), arrival);
            }
        });
        return arrival.ready();
    }

    @Override
    public CompletableFuture<Boolean> teleportToPlayer(PlatformPlayer spectator, PlatformPlayer target) {
        BridgeSession session = sessions.current(spectator.getUniqueId());
        if (session == null || !session.isReady() || !session.has(ServerCapability.TELEPORT)) {
            return CompletableFuture.completedFuture(false);
        }
        UUID targetId = target.getUniqueId();
        return session.request(requestId -> new TeleportToPlayer(requestId, targetId), TELEPORT_TIMEOUT_MILLIS);
    }

    /** Reads the player's game mode and location from the companion on their backend; null without one. */
    @Override
    public CompletableFuture<@Nullable ServerState> playerState(PlatformPlayer player) {
        BridgeSession session = sessions.current(player.getUniqueId());
        if (session == null || !session.isReady() || !session.has(ServerCapability.TELEPORT)) {
            return CompletableFuture.completedFuture(null);
        }
        String server = session.serverName();
        return session.exchange(PlayerState::new, STATE_TIMEOUT_MILLIS).thenApply(reply -> {
            if (!(reply instanceof PlayerStateResult state)) return null;
            GameMode gameMode = gameMode(state.gameMode());
            if (gameMode == null) return null;
            String name = state.world().equals(session.worldUid()) ? session.worldName() : null;
            var world = new BackendWorld(
                    server, state.world(), name == null ? state.world().toString() : name);
            return new ServerState(
                    gameMode, new Location(world, state.x(), state.y(), state.z(), state.yaw(), state.pitch()));
        });
    }

    private static @Nullable GameMode gameMode(int wire) {
        return switch (wire) {
            case SetGameMode.SURVIVAL -> GameMode.SURVIVAL;
            case SetGameMode.CREATIVE -> GameMode.CREATIVE;
            case SetGameMode.ADVENTURE -> GameMode.ADVENTURE;
            case SetGameMode.SPECTATOR -> GameMode.SPECTATOR;
            default -> null;
        };
    }

    @Override
    public void onReady(BridgeSession session) {
        arrived(session, arrivals.get(session.playerId()));
    }

    private static void arrived(@Nullable BridgeSession session, @Nullable Arrival arrival) {
        if (session != null
                && arrival != null
                && session.isReady()
                && arrival.server().equals(session.serverName())) {
            arrival.ready().complete(true);
        }
    }
}
