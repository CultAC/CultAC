package ac.cult.cultac.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflinePlayers;
import ac.cult.cultac.events.packets.listeners.PacketPingListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import ac.grim.grimac.api.event.events.GrimTransactionReceivedEvent;
import ac.grim.grimac.api.event.events.GrimTransactionSendEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransactionEventTest {
    @Test
    void subscribersRegisteredAfterUnobservedTransactionsSeeLaterOnes() {
        OfflinePlayers.installConfig();
        CultPlayer player = OfflinePlayers.player();
        player.user.bind(mock(PlatformPlayer.class));
        var sendChannel = CultAPI.INSTANCE.getEventBus().get(GrimTransactionSendEvent.class);
        var receivedChannel = CultAPI.INSTANCE.getEventBus().get(GrimTransactionReceivedEvent.class);
        List<Integer> sent = new ArrayList<>();
        List<Integer> received = new ArrayList<>();
        GrimTransactionSendEvent.Handler onSend = (user, id, timestamp) -> sent.add(id);
        GrimTransactionReceivedEvent.Handler onReceived = (user, id, cancelled, timestamp) -> received.add(id);
        try {
            assertTrue(sendChannel.isEmpty());
            assertTrue(receivedChannel.isEmpty());
            var listener = new PacketPingListener();
            int unobserved = sendAndAnswer(player, listener);

            sendChannel.onTransactionSend(CultAPI.INSTANCE.getGrimPlugin(), onSend);
            receivedChannel.onTransactionReceived(CultAPI.INSTANCE.getGrimPlugin(), onReceived);
            int observed = sendAndAnswer(player, listener);

            assertTrue(unobserved != observed);
            assertEquals(List.of(observed), sent);
            assertEquals(List.of(observed), received);

            sendChannel.unsubscribe(onSend);
            receivedChannel.unsubscribe(onReceived);
            assertTrue(sendChannel.isEmpty());
            assertTrue(receivedChannel.isEmpty());
            sendAndAnswer(player, listener);

            assertEquals(List.of(observed), sent);
            assertEquals(List.of(observed), received);
        } finally {
            sendChannel.unsubscribe(onSend);
            receivedChannel.unsubscribe(onReceived);
            OfflinePlayers.close(player);
        }
    }

    private static int sendAndAnswer(CultPlayer player, PacketPingListener listener) {
        var transaction = player.createTrackedTransactionPacketForDeferredSend();
        assertNotNull(transaction);
        player.markTrackedTransactionPacketSent(transaction);
        var pong = new ServerboundPong(transaction.id());
        listener.onPong(
                new PacketReceiveEvent<>(player.user, ConnectionPhase.PLAY, ServerboundPackets.PONG, pong),
                player,
                pong);
        return transaction.id();
    }
}
