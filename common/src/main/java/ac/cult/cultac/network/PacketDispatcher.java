package ac.cult.cultac.network;

import ac.cult.cultac.network.event.*;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.player.PlayerObserver;
import ac.cult.cultac.protocol.*;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import java.util.Objects;
import java.util.function.Consumer;

/** Owns registration and dispatch. Each packet retains one immutable route entry. */
public class PacketDispatcher {
    // Bedrock PLAY runs its own engine on the Geyser tick loop. Listeners still see Geyser's
    // Java projection of that movement, with its teleport and tick boundaries, so the projection
    // passes the shared movement gate. Every other PLAY packet is handled by the bridge.
    private static final java.util.Set<PacketType<?>> BEDROCK_BRIDGE_PLAY = java.util.Set.of(
            ServerboundPackets.MOVE_PLAYER,
            ServerboundPackets.MOVE_VEHICLE,
            ServerboundPackets.ACCEPT_TELEPORTATION,
            ServerboundPackets.CLIENT_TICK_END,
            ServerboundPackets.CONFIGURATION_ACKNOWLEDGED,
            ServerboundPackets.CUSTOM_PAYLOAD);
    private final ProtocolRuntime runtime;
    private final PacketHandlerScanner scanner;
    private PacketRouteBuilder registrations;
    private volatile PacketRoutes snapshot;
    private boolean registering;

    public PacketDispatcher(ProtocolRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime);
        scanner = new PacketHandlerScanner(runtime);
        registrations = newRouteBuilder();
        snapshot = registrations.compile();
    }

    public ProtocolRuntime runtime() {
        return runtime;
    }

    public PacketRouteBuilder newRouteBuilder() {
        return new PacketRouteBuilder(scanner);
    }

    public PacketHandlerScanner scanner() {
        return scanner;
    }

    public synchronized void register(Consumer<PacketRouteBuilder> batch) {
        publish(batch, false);
    }

    public synchronized void replace(Consumer<PacketRouteBuilder> batch) {
        publish(batch, true);
    }

    public void clear() {
        replace(ignored -> {});
    }

    private void publish(Consumer<PacketRouteBuilder> batch, boolean replace) {
        if (registering) throw new IllegalStateException("Nested packet registration batch");
        registering = true;
        try {
            var next = replace ? newRouteBuilder() : registrations.copy();
            Objects.requireNonNull(batch).accept(next);
            var routes = next.compile();
            // Do not retain a mutable builder supplied to application code.
            registrations = next.copy();
            snapshot = routes;
        } finally {
            registering = false;
        }
    }

    public Route get(PacketDirection direction, ConnectionPhase phase, int id) {
        return snapshot.get(direction, phase, id);
    }

    public Route get(PacketType<?> type) {
        return snapshot.get(type);
    }

    public record Route(PacketType<?> type, ReceiveRoute<ServerboundPacket> receive, PacketSendRoute<Object> send) {}

    public record ReceiveRoute<R extends ServerboundPacket>(
            PacketReceiveRoute<? super R> early,
            PacketReceiveRoute<? super R> ordinary,
            PacketReceiveRoute<? super R> connection) {
        public <P extends R> void dispatch(PacketReceiveEvent<P> event, CultPlayer player) {
            var original = event.getOriginalPacket();
            early.dispatch(event, player, original);
            if (event.isCancelled()) return;
            ordinary.dispatch(event, player, original);
            connection.dispatch(event, player, event.getPacket());
        }
    }

    public <R extends ServerboundPacket> void receive(PacketReceiveEvent<R> event, ReceiveRoute<? super R> routes) {
        User user = event.getUser();
        if (user == null) return;
        if (!user.getPacketExecutor().inEventLoop()) throw new IllegalStateException("Receive outside packet owner");
        try {
            if (user.getBedrockBridgeConnection() != null
                    && event.getPhase() == ConnectionPhase.PLAY
                    && !BEDROCK_BRIDGE_PLAY.contains(event.getPacketType())) return;
            var player = user.getCultPlayer();
            if (player != null
                    && event.getPacketType() == ServerboundPackets.CLIENT_TICK_END
                    && player.observer != null) player.clientTickEnds.incrementAndGet();
            if (player == null) routes.connection().dispatch(event, null, event.getOriginalPacket());
            else {
                routes.dispatch(event, player);
                PlayerObserver observer = player.observer;
                if (observer != null) observer.received(event.getOriginalPacket(), event.isCancelled());
            }
        } catch (Throwable failure) {
            discardFailedDispatch(event, failure);
        }
    }

    public void send(PacketSendEvent<?> event, PacketSendRoute<Object> route) {
        User user = event.getUser();
        if (user == null || route.isEmpty()) return;
        if (!user.getPacketExecutor().inEventLoop()) throw new IllegalStateException("Send outside packet owner");
        try {
            var player = user.getCultPlayer();
            if (player != null) {
                route.dispatch(event, player, event.getOriginalPacket());
                PlayerObserver observer = player.observer;
                if (observer != null && !event.isCancelled() && !event.isAuthored()) observer.sent(event.getPacket());
            } else route.dispatchConnection(event, event.getOriginalPacket());
        } catch (Throwable failure) {
            discardFailedDispatch(event, failure);
        }
    }

    void discardFailedDispatch(ac.cult.cultac.network.event.PacketEvent<?> event, Throwable failure) {
        event.discardChanges();
        CultNetworkManager.reportFailure(
                "packet handler " + event.getPacketType().key(), failure);
    }
}
