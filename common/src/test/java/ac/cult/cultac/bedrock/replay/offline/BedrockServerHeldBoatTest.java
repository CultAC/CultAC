package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.integration.BedrockProfileState;
import ac.cult.cultac.bedrock.prediction.integration.BedrockVehicleControl;
import ac.cult.cultac.bedrock.prediction.integration.BedrockVehiclePredictionState;
import ac.cult.cultac.bedrock.prediction.state.BedrockBoatProperties;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveVehicle;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.utils.data.TrackerData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;

/**
 * A capture of a boat driven into parked boats shows the server answer every move it rejects with the position it holds
 * the boat at. The client answers the marker after the correction to it before its next tick, takes the correction on
 * the tick after that, and replays the tick between from there; the server answers the moves sent until then too.
 */
public final class BedrockServerHeldBoatTest {
    private static final int BOAT = 71;
    private static final long RUNTIME_ID = 444;
    private static final float OFFSET = 0.375F;
    private static final Vec3 SPAWN = new Vec3(1.5, 118.18112F - OFFSET, 116.60311F);
    private static final Vec3 HELD = new Vec3(1.5, 118.18112F - OFFSET, 115.10311F);

    @Test
    public void theModelTakesTheServersPositionWithTheClientThoughTheServerAnswersAgainMeanwhile() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            PacketEntity boat = spawn(player);
            mount(player);
            var corrections = player.bedrockState.movementCorrections;
            frame(player, boat, 5922);
            frame(player, boat, 5923);

            answer(player, HELD);
            BedrockMovementState sent = frame(player, boat, 5924);
            var correction = corrections.correction(input(player, 5924), boat, sent, 0);
            assertEquals(HELD, correction.position());
            corrections.observe(player, correction);
            corrections.acknowledge(correction.sequence());
            // An answer to the move the client sent before it took the correction.
            answer(player, HELD);

            BedrockMovementState kept = frame(player, boat, 5925);
            // The boat drifts by less than a thousandth a tick; the server holds it 1.5 away.
            assertEquals(SPAWN.z, kept.physicalFeetPosition().z(), 1.0E-3);
            answer(player, HELD);

            BedrockMovementState taken = frame(player, boat, 5926);
            assertEquals(HELD.x, taken.physicalFeetPosition().x(), 1.0E-3);
            assertEquals(HELD.z, taken.physicalFeetPosition().z(), 1.0E-3);

            // The client has moved the boat from there: the same answer now rejects that move.
            assertTrue(answer(player, HELD).isCancelled());
            assertEquals(HELD, corrections.correction(input(player, 5927), boat, taken, 0).position());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static PacketSendEvent<ClientboundMoveVehicle> answer(CultPlayer player, Vec3 position) {
        var packet = new ClientboundMoveVehicle(
                new ac.cult.cultac.protocol.value.Vec3d(position.x, position.y, position.z), 0.0F, 0.0F);
        var event =
                new PacketSendEvent<>(player.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_VEHICLE, packet, false);
        new PacketServerTeleport().onMoveVehicle(event, player, packet);
        return event;
    }

    private static BedrockAuthInputFrame input(CultPlayer player, long tick) {
        return BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(944)
                .clientTick(tick)
                .position(new Vec3(SPAWN.x, SPAWN.y + OFFSET, SPAWN.z))
                .rotation(0, 0, 0)
                .moveVector(0, 0)
                .predictedVehicleId(RUNTIME_ID)
                .predictedVehicleJavaId(BOAT)
                .vehicleRotation(new BedrockAuthInputFrame.VehicleRotation(0, 88.59375F))
                .rawInputFlags(1L << PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE.ordinal())
                .build();
    }

    private static BedrockMovementState frame(CultPlayer player, PacketEntity boat, long tick) {
        var frame = input(player, tick);
        player.bedrockState.offerAuthInputFrame(frame);
        assertNotNull(
                "tick " + tick,
                player.checkManager
                        .getSimulationProcessor()
                        .processBedrockAuthInputFrame(frame, BedrockPredictionTrigger.OFFLINE_REPLAY, null));
        player.checkManager.endBedrockClientTick();
        return BedrockProfileState.previousState(boat.bedrockPrediction.commit().carry());
    }

    /** A boat on water four blocks deep, its surface at y 119. */
    private static PacketEntity spawn(CultPlayer player) {
        var world = player.compensatedWorld;
        int water = DataTables.defaults().registry().block("minecraft:water").defaultState();
        for (int x = -3; x <= 6; x++) {
            for (int z = 110; z <= 121; z++) {
                world.ensureValidationChunkLoaded(x >> 4, z >> 4);
                for (int y = 115; y <= 118; y++) world.updateBlock(x, y, z, water);
            }
        }
        player.bedrockState.offerAuthInputFrame(BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(944)
                .build());
        player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, SPAWN, 0.0F, 0.0F, 0);
        player.compensatedEntities.serverPositionsMap.put(
                BOAT,
                new TrackerData(
                        SPAWN.x,
                        SPAWN.y,
                        SPAWN.z,
                        0.0F,
                        0.0F,
                        EntityTypeIds.OAK_BOAT,
                        player.lastTransactionSent.get()));
        PacketEntity boat = player.compensatedEntities.getEntity(BOAT);
        boat.bedrockPositionOffset = OFFSET;
        boat.bedrockBoat = BedrockBoatProperties.initial(RUNTIME_ID);
        BedrockVehiclePredictionState.initializeBoat(
                boat, new Vec3d(SPAWN.x, SPAWN.y, SPAWN.z), Vec3d.ZERO, 88.59375F, BedrockCoordinateFrame.IDENTITY);
        return boat;
    }

    private static void mount(CultPlayer player) {
        var packet = new ClientboundSetPassengers(BOAT, List.of(player.entityID));
        player.packetEntityReplication.onSetPassengers(
                new PacketSendEvent<>(
                        player.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, packet, false),
                player,
                packet);
        int transaction = player.compensatedEntities.vehicles.serverPlayerVehicleTransaction;
        player.lastTransactionSent.set(transaction);
        player.lastTransactionReceived.set(transaction);
        player.latencyUtils.handleNettySyncTransaction(transaction);
        assertSame(player.compensatedEntities.getEntity(BOAT), BedrockVehicleControl.controlledVehicle(player));
    }
}
