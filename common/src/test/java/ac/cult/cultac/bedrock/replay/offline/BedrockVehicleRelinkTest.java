package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.integration.BedrockProfileState;
import ac.cult.cultac.bedrock.prediction.integration.BedrockVectorAdapter;
import ac.cult.cultac.bedrock.prediction.integration.BedrockVehicleControl;
import ac.cult.cultac.bedrock.prediction.integration.BedrockVehiclePredictionState;
import ac.cult.cultac.bedrock.prediction.state.BedrockBoatProperties;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.utils.data.TrackerData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.Test;

/**
 * The server takes the rider off a paddled boat and puts it back in one tick. A capture shows the client's boat carry
 * on from (17.509441, 18.781483) at 0.17857435 a tick, while the server still held the boat at (17.501822,
 * 18.471031), where the client had it two ticks earlier.
 */
public final class BedrockVehicleRelinkTest {
    private static final int BOAT = 71;
    private static final Vec3 PREDICTED = new Vec3(17.509441F, 118.4375F, 18.781483F);
    private static final Vec3 VELOCITY = new Vec3(0.0043837545F, 0.02256F, 0.17857435F);
    private static final Vec3 SERVER = new Vec3(17.501822F, 118.4375F, 18.471031F);
    private static final float YAW = 88.59375F;

    @Test
    public void riderPutBackOnTheBoatTheClientPredictsKeepsItsPrediction() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            PacketEntity boat = ride(player);
            int removed = send(player);
            int returned = send(player, player.entityID);
            acknowledge(player, removed);
            acknowledge(player, returned);

            assertSame(boat, BedrockVehicleControl.controlledVehicle(player));
            BedrockMovementState state = state(boat);
            assertEquals(PREDICTED, BedrockVectorAdapter.toJava(state.physicalFeetPosition()));
            assertEquals(VELOCITY, BedrockVectorAdapter.toJava(state.velocity()));
            assertEquals(YAW, state.inputFrame().yaw(), 0.0F);
            // Nothing places the boat: no correction is requested and no placement is queued.
            assertFalse(player.bedrockState.movementCorrections.hasPendingCorrection());
            assertEquals(0, player.getSetbackTeleportUtil().vehicleTeleports().queuedVehicleTeleportCount());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void riderPutBackAfterTheClientLeftTheBoatStartsItWhereTheClientShowsIt() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            PacketEntity boat = ride(player);
            acknowledge(player, send(player));
            acknowledge(player, send(player, player.entityID));
            // Nothing places the boat for the mount: the client's first tick in it starts the prediction.
            assertFalse(player.bedrockState.movementCorrections.hasPendingCorrection());
            assertEquals(0, player.getSetbackTeleportUtil().vehicleTeleports().queuedVehicleTeleportCount());

            player.bedrockState.predictedVehicle = boat;
            BedrockVehiclePredictionState.beginClientPrediction(player, boat);
            BedrockMovementState state = state(boat);
            assertEquals(PREDICTED, BedrockVectorAdapter.toJava(state.physicalFeetPosition()));
            assertEquals(Vec3.ZERO, BedrockVectorAdapter.toJava(state.velocity()));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void boatTheServerTeleportsBetweenThePacketsIsCorrectedToTheTarget() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            PacketEntity boat = ride(player);
            int removed = send(player);
            // A teleport of the predicted boat reaches the client only as the correction that places it.
            Vec3 target = new Vec3(30.5F, 118.4375F, 12.5F);
            player.bedrockState.movementCorrections.request(
                    player, BOAT, target, Vec3.ZERO, YAW - 90.0F, 0.0F, false, player.lastTransactionSent.get());
            int returned = send(player, player.entityID);
            acknowledge(player, removed);
            acknowledge(player, returned);

            BedrockMovementState state = state(boat);
            assertEquals(PREDICTED, BedrockVectorAdapter.toJava(state.physicalFeetPosition()));
            assertTrue(player.bedrockState.movementCorrections.hasPendingCorrection());
            var correction = player.bedrockState.movementCorrections.correction(
                    BedrockAuthInputFrame.builder(player.playerUUID)
                            .protocolVersion(944)
                            .clientTick(30)
                            .build(),
                    boat,
                    state,
                    0);
            assertEquals(target, correction.position());
            assertEquals(Vec3.ZERO, correction.velocity());
            assertEquals(YAW, correction.yaw(), 0.0F);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static BedrockMovementState state(PacketEntity boat) {
        return BedrockProfileState.previousState(boat.bedrockPrediction.commit().carry());
    }

    /** Sends the boat's passengers and returns the transaction that proves the client handled them. */
    private static int send(CultPlayer player, int... passengers) {
        var packet = new ClientboundSetPassengers(
                BOAT, java.util.Arrays.stream(passengers).boxed().toList());
        player.packetEntityReplication.onSetPassengers(
                new PacketSendEvent<>(
                        player.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, packet, false),
                player,
                packet);
        int transaction = player.compensatedEntities.vehicles.serverPlayerVehicleTransaction;
        // The next packet's proof is a later transaction.
        player.lastTransactionSent.set(transaction);
        return transaction;
    }

    private static void acknowledge(CultPlayer player, int transaction) {
        player.lastTransactionReceived.set(transaction);
        player.latencyUtils.handleNettySyncTransaction(transaction);
    }

    private static PacketEntity ride(CultPlayer player) {
        player.bedrockState.offerAuthInputFrame(BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(944)
                .build());
        player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, SERVER, YAW - 90.0F, 0.0F, 0);
        player.compensatedEntities.serverPositionsMap.put(
                BOAT,
                new TrackerData(
                        SERVER.x,
                        SERVER.y,
                        SERVER.z,
                        YAW - 90.0F,
                        0.0F,
                        EntityTypeIds.OAK_BOAT,
                        player.lastTransactionSent.get()));
        PacketEntity boat = player.compensatedEntities.getEntity(BOAT);
        boat.bedrockBoat = BedrockBoatProperties.initial(171);
        BedrockVehiclePredictionState.initializeBoat(
                boat,
                new Vec3d(PREDICTED.x, PREDICTED.y, PREDICTED.z),
                new Vec3d(VELOCITY.x, VELOCITY.y, VELOCITY.z),
                YAW,
                BedrockCoordinateFrame.IDENTITY);
        player.compensatedEntities.vehicles.setServerVehicle(
                BOAT, new int[] {player.entityID}, player.lastTransactionSent.get());
        player.compensatedEntities.vehicles.applyVehiclePassengers(BOAT, new int[] {player.entityID});
        player.bedrockState.movementCorrections.clear();
        assertSame(boat, BedrockVehicleControl.controlledVehicle(player));
        return boat;
    }
}
