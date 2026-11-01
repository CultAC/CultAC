package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRemoveEntities;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Entity#setRemoved has no passenger left to eject once the client processed the dismount sent before it. */
class PendingDismountVehicleRemovalTest {
    private static final int VEHICLE = 31;

    @Test
    void removingTheEmptiedVehicleKeepsTheSentDismountPending() {
        OfflineCultTestBootstrap.installConfig();
        User user = TestUsers.create(new User.Profile(UUID.randomUUID(), "Pending_Dismount"), new EmbeddedChannel());
        CultPlayer player = new CultPlayer(user);
        user.bind(mock(PlatformPlayer.class));
        try {
            var vehicles = player.compensatedEntities.vehicles;
            player.compensatedEntities.addEntity(VEHICLE, EntityTypeIds.OAK_BOAT, Vec3.ZERO, 0.0F, 0.0F, 0);
            assertTrue(vehicles.applyVehiclePassengers(VEHICLE, new int[] {player.entityID}));
            vehicles.setServerVehicle(VEHICLE, new int[] {player.entityID}, player.lastTransactionSent.get());

            var dismount = new ClientboundSetPassengers(VEHICLE, List.of());
            player.packetEntityReplication.onSetPassengers(
                    new PacketSendEvent<>(
                            player.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, dismount, false),
                    player,
                    dismount);
            assertTrue(vehicles.hasPendingServerDismount());

            var remove = new ClientboundRemoveEntities(List.of(VEHICLE));
            player.packetEntityReplication.onRemoveEntities(
                    new PacketSendEvent<>(
                            player.user, ConnectionPhase.PLAY, ClientboundPackets.REMOVE_ENTITIES, remove, false),
                    player,
                    remove);
            assertTrue(vehicles.hasPendingServerDismount());
            assertEquals(Integer.valueOf(VEHICLE), vehicles.serverPlayerVehicle);
            assertTrue(vehicles.hasPlayerPassengerState());
        } finally {
            Object channel = player.user.getChannel();
            player.onRemove();
            if (channel instanceof EmbeddedChannel embedded) {
                embedded.runPendingTasks();
                embedded.runScheduledPendingTasks();
                embedded.finishAndReleaseAll();
            }
        }
    }
}
