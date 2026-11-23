package ac.cult.cultac.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.bedrock.replay.offline.OfflinePlayers;
import ac.cult.cultac.network.CultWrite;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetCamera;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A trailing proof that shares the flush bundle's ping is the proof a fresh record of that ping is. */
class SharedTrailingProofTest {
    @Test
    void sharedProofsEqualFreshOnesForEveryPing() {
        OfflinePlayers.installConfig();
        CultPlayer player = OfflinePlayers.player();
        player.user.bind(mock(PlatformPlayer.class));
        try {
            int sent = player.lastTransactionSent.get();
            int[] shared = {41, 41, 42, 7, 7, 41, 42, 42, 0x7fffffff};
            for (int i = 0; i < shared.length; i++) {
                var event = event(player, i);
                event.sharedPing(shared[i]);
                List<CultWrite> before =
                        i % 2 == 0 ? List.of() : List.of(new CultWrite(new ClientboundPing(900 + i), false));
                var proof = player.appendTrailingProofTransaction(event, before);
                assertEquals(new CultPlayer.TrackedTransaction(shared[i], null), proof);
                assertEquals(shared[i], proof.transaction());
                assertNull(proof.packet());
                assertEquals(before, event.getWritesAfterSend());
                assertTrue(event.getWritesBeforeSend().isEmpty());
                assertTrue(event.getTasksAfterSend().isEmpty());
                player.markTrackedTransactionPacketSent(proof);
            }
            assertEquals(sent, player.lastTransactionSent.get());
        } finally {
            OfflinePlayers.close(player);
        }
    }

    private static PacketSendEvent<ClientboundSetCamera> event(CultPlayer player, int entityId) {
        return new PacketSendEvent<>(
                player.user,
                ConnectionPhase.PLAY,
                ClientboundPackets.SET_CAMERA,
                new ClientboundSetCamera(entityId),
                false);
    }
}
