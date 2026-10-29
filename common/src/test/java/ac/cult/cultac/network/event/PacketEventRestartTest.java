package ac.cult.cultac.network.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.network.CultWrite;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundKeepAlive;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import org.junit.jupiter.api.Test;

class PacketEventRestartTest {
    @Test
    void restartedSendEventMatchesNewEvent() {
        var used = new PacketSendEvent<>(
                null, ConnectionPhase.CONFIGURATION, ClientboundPackets.PING, new ClientboundPing(1), true);
        used.setCancelled(true);
        used.cancelFinally();
        used.replace(new ClientboundPing(2));
        used.bundle();
        used.authored();
        used.sharedPing(3);
        used.leadWith(new CultWrite(new ClientboundPing(4), false), 4);
        used.trackedPing(5);
        used.receiptProof(6);
        used.getWritesBeforeSend().add(new CultWrite(new ClientboundPing(7), false));
        used.getWritesAfterSend().add(new CultWrite(new ClientboundPing(8), false));
        used.release();
        assertNull(used.getPacket());
        assertNull(used.getOriginalPacket());

        var packet = new ClientboundKeepAlive(9);
        var restarted = used.restart(null, ConnectionPhase.PLAY, ClientboundPackets.KEEP_ALIVE, packet, false);
        var fresh = new PacketSendEvent<>(null, ConnectionPhase.PLAY, ClientboundPackets.KEEP_ALIVE, packet, false);
        assertSame(used, restarted);
        assertSendState(fresh, restarted);
        assertTrue(restarted.getTimestamp() >= fresh.getTimestamp() - 1000);
    }

    @Test
    void restartChecksLikeTheConstructor() {
        var event = new PacketSendEvent<>(
                null, ConnectionPhase.PLAY, ClientboundPackets.PING, new ClientboundPing(1), false);
        event.release();
        assertThrows(
                NullPointerException.class,
                () -> event.restart(null, null, ClientboundPackets.PING, new ClientboundPing(1), false));
        assertThrows(
                NullPointerException.class,
                () -> event.restart(null, ConnectionPhase.PLAY, ClientboundPackets.PING, null, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> event.restart(null, ConnectionPhase.PLAY, ServerboundPackets.PONG, new ServerboundPong(1), false));
    }

    @Test
    void restartedReceiveEventMatchesNewEvent() {
        var used = new PacketReceiveEvent<>(
                null, ConnectionPhase.PLAY, ServerboundPackets.PONG, new ServerboundPong(1), true);
        used.setCancelled(true);
        used.cancelFinally();
        used.setAcceptedTransactionResponse(true);
        used.setTeleportPositionResponse(true);
        used.release();
        assertNull(used.getPacket());

        var packet = new ServerboundPong(2);
        var restarted = used.restart(null, ConnectionPhase.CONFIGURATION, ServerboundPackets.PONG, packet);
        var fresh = new PacketReceiveEvent<>(null, ConnectionPhase.CONFIGURATION, ServerboundPackets.PONG, packet);
        assertSame(used, restarted);
        assertEquals(fresh.getUser(), restarted.getUser());
        assertEquals(fresh.getPhase(), restarted.getPhase());
        assertSame(fresh.getPacketType(), restarted.getPacketType());
        assertSame(fresh.getPacket(), restarted.getPacket());
        assertSame(fresh.getOriginalPacket(), restarted.getOriginalPacket());
        assertEquals(fresh.isCancelled(), restarted.isCancelled());
        assertEquals(fresh.isReplaced(), restarted.isReplaced());
        assertEquals(fresh.isSynthetic(), restarted.isSynthetic());
        assertEquals(fresh.isAcceptedTransactionResponse(), restarted.isAcceptedTransactionResponse());
        assertEquals(fresh.isTeleportPositionResponse(), restarted.isTeleportPositionResponse());
        restarted.setCancelled(false);
        assertFalse(restarted.isCancelled());
    }

    private static void assertSendState(PacketSendEvent<?> expected, PacketSendEvent<?> actual) {
        assertEquals(expected.getUser(), actual.getUser());
        assertEquals(expected.getPhase(), actual.getPhase());
        assertSame(expected.getPacketType(), actual.getPacketType());
        assertSame(expected.getPacket(), actual.getPacket());
        assertSame(expected.getOriginalPacket(), actual.getOriginalPacket());
        assertEquals(expected.isCancelled(), actual.isCancelled());
        assertEquals(expected.isReplaced(), actual.isReplaced());
        assertEquals(expected.isInsideBundle(), actual.isInsideBundle());
        assertEquals(expected.isBundleRequested(), actual.isBundleRequested());
        assertEquals(expected.isAuthored(), actual.isAuthored());
        assertEquals(expected.getDecodedProtocol(), actual.getDecodedProtocol());
        assertEquals(expected.flushPing(), actual.flushPing());
        assertSame(expected.leadingPing(), actual.leadingPing());
        assertEquals(expected.trackedPing(), actual.trackedPing());
        assertEquals(expected.receiptProof(), actual.receiptProof());
        assertEquals(expected.writesBefore(), actual.writesBefore());
        assertEquals(expected.writesAfter(), actual.writesAfter());
        assertEquals(expected.tasksAfter(), actual.tasksAfter());
        actual.setCancelled(true);
        actual.discardChanges();
        assertFalse(actual.isCancelled());
    }
}
