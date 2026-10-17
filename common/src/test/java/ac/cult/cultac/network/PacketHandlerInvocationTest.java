package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import org.junit.jupiter.api.Test;

class PacketHandlerInvocationTest {
    private final PacketHandlerScanner scanner =
            new PacketHandlerScanner(TestProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V26_3)));

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void handlersReceiveTheEventAndPacket() {
        var listener = new PrivateDuplex();
        var receive = scanner.receiveHandlers(listener).getFirst().handler();
        var send = scanner.sendHandlers(listener).getFirst().handler();
        var pong = new ServerboundPong(7);
        var ping = new ClientboundPing(8);
        var received = new PacketReceiveEvent<>(null, ConnectionPhase.PLAY, ServerboundPackets.PONG, pong);
        var sent = new PacketSendEvent<>(null, ConnectionPhase.PLAY, ClientboundPackets.PING, ping, false);

        receive.handle(received, null, pong);
        assertSame(received, listener.event);
        assertSame(pong, listener.packet);
        send.handle((PacketSendEvent) sent, null, ping);
        assertSame(sent, listener.event);
        assertSame(ping, listener.packet);
        receive.handle(null, null, null);
        assertNull(listener.packet);
        assertEquals(3, listener.calls);
    }

    @Test
    void wrongPacketTypeThrows() {
        var listener = new PrivateDuplex();
        var receive = scanner.receiveHandlers(listener).getFirst().handler();
        var send = scanner.sendHandlers(listener).getFirst().handler();
        assertThrows(ClassCastException.class, () -> receive.handle(null, null, new ClientboundPing(8)));
        assertThrows(ClassCastException.class, () -> send.handle(null, null, new ServerboundPong(7)));
        assertEquals(0, listener.calls);
    }

    @Test
    void handlerFailuresKeepTheirIdentity() {
        var listener = new PrivateDuplex();
        var registration = scanner.receiveHandlers(listener).getFirst();
        for (Throwable failure :
                new Throwable[] {new Exception("checked"), new IllegalArgumentException("runtime"), new AssertionError()
                }) {
            listener.failure = failure;
            Throwable thrown = assertThrows(
                    Throwable.class, () -> registration.handler().handle(null, null, new ServerboundPong(7)));
            if (failure instanceof RuntimeException || failure instanceof Error) {
                assertSame(failure, thrown);
            } else {
                assertInstanceOf(IllegalStateException.class, thrown);
                assertEquals("Packet handler failed: " + registration.method(), thrown.getMessage());
                assertSame(failure, thrown.getCause());
            }
        }
    }

    @Test
    void defaultAndRawHandlersAreCallable() {
        var listener = new DefaultAndRaw();
        var pong = new ServerboundPong(7);
        var ping = new ClientboundPing(8);
        scanner.receiveHandlers(listener).getFirst().handler().handle(null, null, pong);
        assertSame(pong, listener.packet);
        scanner.sendHandlers(listener).getFirst().handler().handle(null, null, ping);
        assertSame(ping, listener.packet);
        assertEquals(2, listener.calls);
    }

    static class PrivateDuplex {
        Object event, packet;
        int calls;
        Throwable failure;

        @CultPacketHandler
        private void pong(PacketReceiveEvent<ServerboundPong> event, CultPlayer player, ServerboundPong packet)
                throws Throwable {
            seen(event, packet);
        }

        @CultPacketHandler
        private void ping(PacketSendEvent<ClientboundPing> event, CultPlayer player, ClientboundPing packet)
                throws Throwable {
            seen(event, packet);
        }

        private void seen(Object event, Object packet) throws Throwable {
            if (failure != null) throw failure;
            this.event = event;
            this.packet = packet;
            calls++;
        }
    }

    interface DefaultWildcard {
        void seen(Object packet);

        @CultPacketHandler
        default void pong(PacketReceiveEvent<?> event, CultPlayer player, ServerboundPong packet) {
            seen(packet);
        }
    }

    static class DefaultAndRaw implements DefaultWildcard {
        Object packet;
        int calls;

        @Override
        public void seen(Object packet) {
            this.packet = packet;
            calls++;
        }

        @SuppressWarnings("rawtypes")
        @CultPacketHandler
        private void ping(PacketSendEvent event, CultPlayer player, ClientboundPing packet) {
            seen(packet);
        }
    }
}
