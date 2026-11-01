package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolRuntime;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class PacketHandlerScannerTest {
    private final PacketHandlerScanner scanner =
            new PacketHandlerScanner(ProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V26_3)));

    @Test
    void aMovementFamilyRegistersOnceAndTheMethodReceivesItsDecodedVariant() {
        var listener = new Movement();
        var registrations = scanner.receiveHandlers(listener);
        assertEquals(1, registrations.size());
        assertSame(ServerboundPackets.MOVE_PLAYER, registrations.getFirst().packetType());
        for (boolean position : List.of(false, true))
            for (boolean rotation : List.of(false, true)) {
                var packet = new ServerboundMovePlayer(1, 2, 3, 4, 5, false, false, position, rotation);
                registrations.getFirst().handler().handle(null, null, packet);
                assertSame(packet, listener.packet);
            }
    }

    @Test
    void inheritedAndDefaultMethodsAreDiscoveredAndAnOverrideShadowsTheParent() {
        var listener = new Child();
        var registrations = scanner.receiveHandlers(listener);
        assertEquals(2, registrations.size());
        for (var registration : registrations) {
            if (registration.packetType() == ServerboundPackets.PONG)
                registration.handler().handle(null, null, new ServerboundPong(7));
            else
                registration
                        .handler()
                        .handle(null, null, new ServerboundMovePlayer(1, 2, 3, 4, 5, false, false, true, true));
        }
        assertEquals(1, listener.moves);
        assertEquals(1, listener.pongs);
    }

    @Test
    void duplicatesAreRejectedForTheWholeFamilyBeforeAnyCallbackIsPublished() {
        assertThrows(IllegalStateException.class, () -> scanner.receiveHandlers(new Duplicate()));
    }

    @Test
    void opaqueDeclarationsUseTheFamilyIdentityAndOptionalFamiliesMayBeAbsent() {
        var registration = scanner.receiveHandlers(new Loaded()).getFirst();
        assertSame(ServerboundPackets.PLAYER_LOADED, registration.packetType());
        registration.handler().handle(null, null, ServerboundPackets.PLAYER_LOADED.opaqueValue());
        var older = new PacketHandlerScanner(ProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V1_21_3)));
        assertTrue(older.hasReceiveHandlerDeclaration(Loaded.class));
        assertTrue(older.receiveHandlers(new Loaded()).isEmpty());
    }

    @Test
    void unknownCatalogKeysWrongDirectionsAndForeignTypesAreRejected() {
        assertThrows(IllegalStateException.class, () -> scanner.receiveHandlers(new Unknown()));
        assertThrows(IllegalStateException.class, () -> scanner.receiveHandlers(new WrongDirection()));
        var source = ServerboundPackets.PONG;
        var foreign = new ac.cult.cultac.protocol.PacketType<>(
                source.key(),
                source.recordClass(),
                source.direction(),
                source.phases(),
                source.wireNames(),
                source.since(),
                source.codec());
        assertThrows(IllegalArgumentException.class, () -> scanner.supports(foreign));
        assertEquals(
                List.of(ServerboundPackets.MOVE_PLAYER),
                scanner.packetTypes(Movement.class, PacketDirection.SERVERBOUND));
    }

    @Test
    void eachDirectionBindsOnlyItsOwnCallbacks() {
        var listener = new Duplex();
        var receives = scanner.receiveHandlers(listener);
        var sends = scanner.sendHandlers(listener);
        assertEquals(
                List.of(ServerboundPackets.PONG),
                receives.stream()
                        .map(PacketHandlerScanner.ReceiveRegistration::packetType)
                        .toList());
        assertEquals(
                List.of(ClientboundPackets.PING),
                sends.stream()
                        .map(PacketHandlerScanner.SendRegistration::packetType)
                        .toList());
        receives.getFirst().handler().handle(null, null, new ServerboundPong(7));
        assertEquals(7, listener.received);
        assertEquals(0, listener.sent);
        sends.getFirst().handler().handle(null, null, new ClientboundPing(8));
        assertEquals(7, listener.received);
        assertEquals(8, listener.sent);
    }

    @Test
    void requestingOneDirectionStillValidatesDeclarationsInTheOtherDirection() {
        assertThrows(IllegalStateException.class, () -> scanner.receiveHandlers(new InvalidSend()));
        assertThrows(IllegalStateException.class, () -> scanner.sendHandlers(new InvalidReceive()));
    }

    @Test
    void everyHandlerShapeIsCalledThroughASpunClass() {
        for (Class<?> type : List.of(
                Movement.class, Child.class, Duplex.class, Loaded.class, Throwing.class, PrivateHandler.class)) {
            for (var entry : scanner.schema(type).entries()) {
                assertFalse(
                        entry.invoker() instanceof PacketHandlerScanner.HandleInvoker,
                        entry.method().toString());
            }
        }
    }

    @Test
    void privateHandlersAreBoundAndCalled() {
        var listener = new PrivateHandler();
        scanner.receiveHandlers(listener).getFirst().handler().handle(null, null, new ServerboundPong(3));
        assertEquals(3, listener.received);
    }

    @Test
    void unwrappedFailuresPropagateAsTheSameInstanceInBothDirections() {
        var listener = new Throwing();
        PacketReceiveHandler<Object> receive =
                scanner.receiveHandlers(listener).getFirst().handler();
        PacketSendHandler<Object> send =
                scanner.sendHandlers(listener).getFirst().handler();
        for (Throwable failure : List.<Throwable>of(
                new IllegalArgumentException("runtime"), new AssertionError("error"), new LinkageError("linkage"))) {
            listener.failure = failure;
            assertSame(
                    failure, assertThrows(Throwable.class, () -> receive.handle(null, null, new ServerboundPong(1))));
            assertSame(failure, assertThrows(Throwable.class, () -> send.handle(null, null, new ClientboundPing(1))));
        }
    }

    @Test
    void checkedFailuresAreWrappedWithTheHandlerMethod() throws NoSuchMethodException {
        var listener = new Throwing();
        var receive = scanner.receiveHandlers(listener).getFirst();
        var send = scanner.sendHandlers(listener).getFirst();
        for (Throwable failure : List.<Throwable>of(new IOException("checked"), new Throwable("raw"))) {
            listener.failure = failure;
            var received = assertThrows(
                    IllegalStateException.class, () -> receive.handler().handle(null, null, new ServerboundPong(1)));
            assertEquals("Packet handler failed: " + receive.method(), received.getMessage());
            assertSame(failure, received.getCause());
            var sent = assertThrows(
                    IllegalStateException.class, () -> send.handler().handle(null, null, new ClientboundPing(1)));
            assertEquals("Packet handler failed: " + send.method(), sent.getMessage());
            assertSame(failure, sent.getCause());
        }
        assertEquals(
                Throwing.class.getDeclaredMethod(
                        "pong", PacketReceiveEvent.class, CultPlayer.class, ServerboundPong.class),
                receive.method());
    }

    @Test
    void spunAndReflectiveInvokersBehaveTheSame() throws Throwable {
        for (Class<?> type : List.of(Movement.class, Child.class, Duplex.class, PrivateHandler.class)) {
            for (var entry : scanner.schema(type).entries()) {
                var reflective = new PacketHandlerScanner.HandleInvoker(
                        PacketHandlerScanner.unboundHandle(type, entry.method()));
                Object packet = entry.packetType().recordClass() == ServerboundMovePlayer.class
                        ? new ServerboundMovePlayer(1, 2, 3, 4, 5, false, false, true, true)
                        : entry.packetType().recordClass() == ServerboundPong.class
                                ? new ServerboundPong(9)
                                : new ClientboundPing(9);
                Object spunListener = type.getDeclaredConstructor().newInstance();
                Object reflectiveListener = type.getDeclaredConstructor().newInstance();
                entry.invoker().call(spunListener, null, null, packet);
                reflective.call(reflectiveListener, null, null, packet);
                assertEquals(
                        state(reflectiveListener),
                        state(spunListener),
                        entry.method().toString());
                assertThrows(ClassCastException.class, () -> entry.invoker().call(spunListener, null, null, "x"));
                assertThrows(ClassCastException.class, () -> reflective.call(reflectiveListener, null, null, "x"));
                if (!entry.method().getDeclaringClass().isInterface()) {
                    assertThrows(ClassCastException.class, () -> entry.invoker().call("x", null, null, packet));
                    assertThrows(ClassCastException.class, () -> reflective.call("x", null, null, packet));
                }
            }
        }
        var throwing = new Throwing();
        var entry = scanner.schema(Throwing.class).entries().getFirst();
        var reflective = new PacketHandlerScanner.HandleInvoker(
                PacketHandlerScanner.unboundHandle(Throwing.class, entry.method()));
        throwing.failure = new IOException("checked");
        Object packet = entry.packetType().recordClass() == ServerboundPong.class
                ? new ServerboundPong(1)
                : new ClientboundPing(1);
        assertSame(
                throwing.failure,
                assertThrows(Throwable.class, () -> entry.invoker().call(throwing, null, null, packet)));
        assertSame(
                throwing.failure, assertThrows(Throwable.class, () -> reflective.call(throwing, null, null, packet)));
    }

    private static String state(Object listener) {
        return switch (listener) {
            case Movement movement -> String.valueOf(movement.packet);
            case Child child -> child.moves + "/" + child.pongs;
            case Duplex duplex -> duplex.received + "/" + duplex.sent;
            case PrivateHandler handler -> String.valueOf(handler.received);
            default -> throw new AssertionError(listener);
        };
    }

    static class Throwing {
        Throwable failure;

        @CultPacketHandler
        void pong(PacketReceiveEvent<ServerboundPong> event, CultPlayer player, ServerboundPong packet)
                throws Throwable {
            throw failure;
        }

        @CultPacketHandler
        void ping(PacketSendEvent<ClientboundPing> event, CultPlayer player, ClientboundPing packet) throws Throwable {
            throw failure;
        }
    }

    static class PrivateHandler {
        int received;

        @CultPacketHandler
        private void pong(PacketReceiveEvent<ServerboundPong> event, CultPlayer player, ServerboundPong packet) {
            received = packet.id();
        }
    }

    static class Duplex {
        int received, sent;

        @CultPacketHandler
        void pong(PacketReceiveEvent<ServerboundPong> event, CultPlayer player, ServerboundPong packet) {
            received = packet.id();
        }

        @CultPacketHandler
        void ping(PacketSendEvent<ClientboundPing> event, CultPlayer player, ClientboundPing packet) {
            sent = packet.id();
        }
    }

    static class InvalidSend extends Duplex {
        @CultPacketHandler
        void duplicatePing(PacketSendEvent<ClientboundPing> event, CultPlayer player, ClientboundPing packet) {}
    }

    static class InvalidReceive extends Duplex {
        @CultPacketHandler
        void duplicatePong(PacketReceiveEvent<ServerboundPong> event, CultPlayer player, ServerboundPong packet) {}
    }

    static class Movement {
        ServerboundMovePlayer packet;

        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
            this.packet = packet;
        }
    }

    interface DefaultPong {
        void pongSeen();

        @CultPacketHandler
        default void pong(PacketReceiveEvent<ServerboundPong> event, CultPlayer player, ServerboundPong packet) {
            pongSeen();
        }
    }

    static class Parent {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
            fail("Shadowed parent called");
        }
    }

    static class Child extends Parent implements DefaultPong {
        int moves, pongs;

        @Override
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
            moves++;
        }

        @Override
        public void pongSeen() {
            pongs++;
        }
    }

    static class Duplicate {
        @CultPacketHandler
        void first(PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {}

        @CultPacketHandler
        void second(PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {}
    }

    static class Loaded {
        @CultPacketHandler("serverbound.player_loaded")
        void loaded(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
            assertSame(ServerboundPackets.PLAYER_LOADED, packet.type());
        }
    }

    static class Unknown {
        @CultPacketHandler("serverbound.unknown")
        void unknown(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {}
    }

    static class WrongDirection {
        @CultPacketHandler("clientbound.login_finished")
        void wrong(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {}
    }
}
