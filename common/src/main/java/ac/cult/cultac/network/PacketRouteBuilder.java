package ac.cult.cultac.network;

import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPacket;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/** A private registration batch; only compiled immutable routes reach packet threads. */
public final class PacketRouteBuilder {
    private final PacketHandlerScanner scanner;
    private final java.util.Map<PacketType<?>, java.util.List<ReceiveRegistration>> receives =
            new java.util.HashMap<>();
    private final java.util.Map<PacketType<?>, java.util.List<SendRegistration>> sends = new java.util.HashMap<>();

    public PacketRouteBuilder(PacketHandlerScanner scanner) {
        this.scanner = java.util.Objects.requireNonNull(scanner);
    }

    PacketRouteBuilder copy() {
        var copy = new PacketRouteBuilder(scanner);
        receives.forEach((type, handlers) -> copy.receives.put(type, new java.util.ArrayList<>(handlers)));
        sends.forEach((type, handlers) -> copy.sends.put(type, new java.util.ArrayList<>(handlers)));
        return copy;
    }

    public enum ReceiveStage {
        EARLY,
        ORDINARY,
        CONNECTION
    }

    public void receiveOpaque(List<PacketType<Opaque>> types, PacketReceiveHandler<Opaque> handler) {
        receiveOpaque(types, PacketListenerPriority.NORMAL, handler);
    }

    public void receiveOpaque(
            List<PacketType<Opaque>> types, PacketListenerPriority priority, PacketReceiveHandler<Opaque> handler) {
        java.util.Objects.requireNonNull(handler);
        for (var type : types) {
            if (!type.isOpaque()) throw new IllegalArgumentException("Not an opaque family: " + type);
            if (type.direction() != PacketDirection.SERVERBOUND)
                throw new IllegalArgumentException("Not a serverbound family: " + type);
            scanner.supports(type);
        }
        for (var type : types) {
            receive(type, priority, handler);
        }
    }

    public PacketHandlerScanner scanner() {
        return scanner;
    }

    public java.util.List<PacketType<?>> packetTypes(Class<?> listenerClass, PacketDirection direction) {
        return scanner.packetTypes(listenerClass, direction);
    }

    public void earlyReceiveRoute(
            PacketType<?> route, PacketListenerPriority priority, PacketReceiveHandler<Object> handler) {
        addReceive(route, priority, handler, true);
    }

    public void sendRoute(PacketType<?> route, PacketListenerPriority priority, PacketSendHandler<Object> handler) {
        addSend(route, priority, handler, false);
    }

    /** A typed send callback that also runs before a player exists, or after it was removed. */
    public <R extends ClientboundPacket> void sendConnection(
            PacketType<R> type, PacketListenerPriority priority, BiConsumer<PacketSendEvent<R>, R> handler) {
        Objects.requireNonNull(handler);
        @SuppressWarnings({"unchecked", "rawtypes"})
        PacketSendHandler<Object> checked = (event, player, packet) ->
                handler.accept((PacketSendEvent) event, type.recordClass().cast(packet));
        if (scanner.supports(type)) {
            addSend(type, priority, checked, true);
        }
    }

    public <R extends ServerboundPacket> void receive(
            PacketType<R> type, PacketListenerPriority priority, PacketReceiveHandler<? super R> handler) {
        receive(type, priority, handler, false);
    }

    public <R extends ServerboundPacket> void earlyReceive(
            PacketType<R> type, PacketListenerPriority priority, PacketReceiveHandler<? super R> handler) {
        receive(type, priority, handler, true);
    }

    /** A per-type connection callback, after player callbacks, also before a player exists. */
    public <R extends ServerboundPacket> void receiveConnection(
            PacketType<R> type,
            PacketListenerPriority priority,
            java.util.function.BiConsumer<ac.cult.cultac.network.event.PacketReceiveEvent<R>, R> handler) {
        java.util.Objects.requireNonNull(handler);
        PacketReceiveHandler<Object> checked = (event, player, packet) ->
                handler.accept(event, type.recordClass().cast(packet));
        if (scanner.supports(type)) {
            addConnectionReceive(type, priority, checked);
        }
    }

    private <R extends ServerboundPacket> void receive(
            PacketType<R> type,
            PacketListenerPriority priority,
            PacketReceiveHandler<? super R> handler,
            boolean early) {
        java.util.Objects.requireNonNull(handler);
        PacketReceiveHandler<Object> checked = (event, player, packet) ->
                handler.handle(event, player, type.recordClass().cast(packet));
        if (scanner.supports(type)) {
            addReceive(type, priority, checked, early);
        }
    }

    public <R extends ClientboundPacket> void send(
            PacketType<R> type, PacketListenerPriority priority, PacketSendHandler<? super R> handler) {
        java.util.Objects.requireNonNull(handler);
        PacketSendHandler<Object> checked = (event, player, packet) -> handler.handle(
                (ac.cult.cultac.network.event.PacketSendEvent) event,
                player,
                type.recordClass().cast(packet));
        if (scanner.supports(type)) {
            addSend(type, priority, checked, false);
        }
    }

    public void registerReceiveListener(PacketListenerPriority priority, Object listener) {
        registerListener(priority, listener, true, false);
    }

    public void registerSendListener(PacketListenerPriority priority, Object listener) {
        registerListener(priority, listener, false, true);
    }

    public void registerListener(PacketListenerPriority priority, Object listener) {
        registerListener(priority, listener, true, true);
    }

    private void registerListener(PacketListenerPriority priority, Object listener, boolean receive, boolean send) {
        // Resolve and validate the entire listener before publishing any callback.
        if (!(receive
                        && (scanner.hasReceiveHandlerDeclaration(listener.getClass())
                                || listener instanceof OpaqueReceiveListener))
                && !(send && scanner.hasSendHandlerDeclaration(listener.getClass()))) {
            throw new IllegalStateException("No " + (receive && send ? "" : receive ? "receive " : "send ")
                    + "@CultPacketHandler methods on " + listener.getClass().getName());
        }
        if (receive) {
            for (var registration : scanner.receiveHandlers(listener)) {
                addReceive(registration.packetType(), priority, registration.handler(), false);
            }
            if (listener instanceof OpaqueReceiveListener opaque) opaque.registerOpaqueReceivePackets(this, priority);
        }
        if (send) {
            for (var registration : scanner.sendHandlers(listener)) {
                addSend(registration.packetType(), priority, registration.handler(), false);
            }
        }
    }

    private void addReceive(
            PacketType<?> type, PacketListenerPriority priority, PacketReceiveHandler<Object> handler, boolean early) {
        receiveRoute(type, priority, handler, early ? ReceiveStage.EARLY : ReceiveStage.ORDINARY);
    }

    private void addConnectionReceive(
            PacketType<?> type, PacketListenerPriority priority, PacketReceiveHandler<Object> handler) {
        receiveRoute(type, priority, handler, ReceiveStage.CONNECTION);
    }

    public void receiveRoute(
            PacketType<?> type,
            PacketListenerPriority priority,
            PacketReceiveHandler<Object> handler,
            ReceiveStage stage) {
        java.util.Objects.requireNonNull(priority);
        java.util.Objects.requireNonNull(handler);
        java.util.Objects.requireNonNull(stage);
        if (type.direction() != PacketDirection.SERVERBOUND)
            throw new IllegalArgumentException("Not serverbound: " + type);
        if (scanner.supports(type))
            receives.computeIfAbsent(type, ignored -> new java.util.ArrayList<>())
                    .add(new ReceiveRegistration(priority, stage, handler));
    }

    private void addSend(
            PacketType<?> type,
            PacketListenerPriority priority,
            PacketSendHandler<Object> handler,
            boolean connection) {
        java.util.Objects.requireNonNull(priority);
        java.util.Objects.requireNonNull(handler);
        if (type.direction() != PacketDirection.CLIENTBOUND)
            throw new IllegalArgumentException("Not clientbound: " + type);
        if (scanner.supports(type))
            sends.computeIfAbsent(type, ignored -> new java.util.ArrayList<>())
                    .add(new SendRegistration(priority, handler, connection));
    }

    public java.util.Map<PacketType<?>, PacketReceiveRoute<Object>> receiveRoutes(ReceiveStage stage) {
        var result = new java.util.HashMap<PacketType<?>, PacketReceiveRoute<Object>>();
        receives.forEach((type, registrations) -> {
            var route = compileReceive(registrations, stage);
            if (!route.isEmpty()) result.put(type, route);
        });
        return java.util.Map.copyOf(result);
    }

    public java.util.Map<PacketType<?>, PacketSendRoute<Object>> sendRoutes() {
        var result = new java.util.HashMap<PacketType<?>, PacketSendRoute<Object>>();
        sends.forEach((type, registrations) -> result.put(type, compileSend(registrations)));
        return java.util.Map.copyOf(result);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static PacketReceiveRoute<Object> compileReceive(
            List<ReceiveRegistration> registrations, ReceiveStage stage) {
        return PacketReceiveRoute.of(registrations.stream()
                .filter(r -> r.stage() == stage)
                .sorted(java.util.Comparator.comparing(ReceiveRegistration::priority))
                .map(ReceiveRegistration::handler)
                .toArray(PacketReceiveHandler[]::new));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static PacketSendRoute<Object> compileSend(List<SendRegistration> registrations) {
        var sorted = registrations.stream()
                .sorted(java.util.Comparator.comparing(SendRegistration::priority))
                .toList();
        return PacketSendRoute.of(
                sorted.stream().map(SendRegistration::handler).toArray(PacketSendHandler[]::new),
                sorted.stream()
                        .filter(SendRegistration::connection)
                        .map(SendRegistration::handler)
                        .toArray(PacketSendHandler[]::new));
    }

    PacketRoutes compile() {
        var runtime = scanner.runtime();
        var entries = new PacketDispatcher.Route[runtime.slotCount()];
        receives.forEach((type, registrations) -> {
            var route = new PacketDispatcher.ReceiveRoute<ServerboundPacket>(
                    compileReceive(registrations, ReceiveStage.EARLY),
                    compileReceive(registrations, ReceiveStage.ORDINARY),
                    compileReceive(registrations, ReceiveStage.CONNECTION));
            entries[runtime.slot(type)] = new PacketDispatcher.Route(type, route, null);
        });
        sends.forEach((type, registrations) ->
                entries[runtime.slot(type)] = new PacketDispatcher.Route(type, null, compileSend(registrations)));
        return new PacketRoutes(runtime, entries);
    }

    private record ReceiveRegistration(
            PacketListenerPriority priority, ReceiveStage stage, PacketReceiveHandler<Object> handler) {}

    private record SendRegistration(
            PacketListenerPriority priority, PacketSendHandler<Object> handler, boolean connection) {}
}
