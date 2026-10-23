package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import org.junit.jupiter.api.Test;

/** The server's camera is what it last sent, on the send timeline. */
class ServerCameraTest {
    @Test
    void aCameraSentAfterARespawnOutlivesTheRespawnsAcknowledgement() {
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            fixture.user.bind(mock(PlatformPlayer.class));
            var player = fixture.player;
            sendRespawn(fixture);
            assertFalse(player.cameraEntity.isServerCamera(7), "ClientPacketListener#handleRespawn resets the camera");
            player.cameraEntity.onSetCamera(7);
            int sent = player.lastTransactionSent.get();
            assertTrue(sent > player.lastTransactionReceived.get(), "The respawn and camera are proven");
            // The client answers the respawn's proofs, then the camera's.
            for (int id = player.lastTransactionReceived.get() + 1; id <= sent; id++) {
                player.lastTransactionReceived.set(id);
                player.latencyUtils.handleNettySyncTransaction(id);
            }
            assertTrue(player.cameraEntity.isServerCamera(7));
        }
    }

    private static void sendRespawn(RecordReceiveFixture fixture) {
        var spawnInfo = new PlayerSpawnInfo(
                ModelRegistryNamesState.defaults().id("minecraft:dimension_type", "minecraft:the_end"),
                fixture.player.compensatedWorld.getLastClientboundDimension().dimension(),
                GameMode.SURVIVAL);
        var packet = new ClientboundRespawn(spawnInfo, (byte) 0);
        var event =
                new PacketSendEvent<>(fixture.user, ConnectionPhase.PLAY, ClientboundPackets.RESPAWN, packet, false);
        new PacketPlayerRespawn().onRespawn(event, fixture.player, packet);
        event.tasksAfter().forEach(Runnable::run);
    }
}
