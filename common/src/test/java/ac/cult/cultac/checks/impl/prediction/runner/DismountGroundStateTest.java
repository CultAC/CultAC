package ac.cult.cultac.checks.impl.prediction.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Player#tick clears a passenger's ground state every ride tick, so a confirmed dismount starts off ground. */
class DismountGroundStateTest {
    private static final int VEHICLE = 31;

    @Test
    void aConfirmedDismountClearsTheGroundState() {
        OfflineCultTestBootstrap.installConfig();
        User user = TestUsers.create(new User.Profile(UUID.randomUUID(), "Dismount_Ground"), new EmbeddedChannel());
        CultPlayer player = new CultPlayer(user);
        user.bind(mock(PlatformPlayer.class));
        try {
            var vehicles = player.compensatedEntities.vehicles;
            player.compensatedEntities.addEntity(VEHICLE, EntityTypeIds.PIG, Vec3.ZERO, 0.0F, 0.0F, 0);
            assertTrue(vehicles.applyVehiclePassengers(VEHICLE, new int[] {player.entityID}));
            vehicles.setServerVehicle(VEHICLE, new int[] {player.entityID}, player.lastTransactionSent.get());
            player.onGround = true;
            SimulationProcessor simulation = player.checkManager.getSimulationProcessor();
            // This assertion concerns a completed ride, not just two passenger packet handlers.
            simulation.recordRideTick();
            simulation.setLastOnGround(DesyncStatus.TRUE);

            var dismount = new ClientboundSetPassengers(VEHICLE, List.of());
            var event = new PacketSendEvent<>(
                    player.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, dismount, false);
            player.packetEntityReplication.onSetPassengers(event, player, dismount);
            event.tasksAfter().forEach(Runnable::run);
            for (var write : event.writesAfter()) {
                if (write.packet() instanceof ClientboundPing ping)
                    assertTrue(player.addTransactionResponse(ping.id()));
            }

            assertFalse(player.compensatedEntities.getSelf().inVehicle());
            assertFalse(player.onGround);
            assertEquals(DesyncStatus.FALSE, simulation.lastOnGround);
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
