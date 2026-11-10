package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import ac.cult.blocksim.data.BlockIds;
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
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.TrackerData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;

/**
 * A ridden boat breaks the lily pads it reaches in the client's world only. Captures of a boat driven at a pad at
 * (18, 120, 70) show its velocity halved once on the tick it reaches the pad and not on the ticks after, although no
 * packet removed the pad; once the server sent the pad again, the boat was slowed by it again.
 */
public final class BedrockBoatLilyPadBreakTest {
    private static final int BOAT = 71;
    private static final long RUNTIME_ID = 171;
    private static final BlockPos PAD = new BlockPos(18, 120, 70);
    /** The boat's feet one tick before it reaches the pad; the reported position is 0.375 higher. */
    private static final Vec3d START = new Vec3d(17.566694F, 119.307434F - 0.375F, 71.320305F);
    /** Carried into the tick; an unpaddled boat on water keeps about 0.81 of its x and z through its move. */
    private static final Vec3d VELOCITY = new Vec3d(0.008586454F / 0.81F, 0.07018889F, 0.34977338F / 0.81F);

    @Test
    public void reachedPadSlowsTheBoatOnceAndStaysBrokenUntilTheServerSendsItAgain() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            var world = player.compensatedWorld;
            int lilyPad = state("minecraft:lily_pad");
            int air = BlockIds.AIR.defaultState();
            PacketEntity boat = ride(player, true);
            Vec3d open = openWaterVelocity();

            // The move ends within reach of the pad: half the velocity of the same move over open water, with
            // gravity and buoyancy applied to the halved y.
            BedrockMovementState reached = frame(player, boat, 184507);
            assertEquals((float) open.x() * 0.5F, (float) reached.velocity().x(), 0);
            assertEquals((float) open.z() * 0.5F, (float) reached.velocity().z(), 0);
            assertEquals(0.04663479F, (float) reached.velocity().y(), 1.0E-6F);

            // Only the client's world lost the pad.
            assertEquals(air, world.getBlockStateIdAt(PAD));
            assertEquals(lilyPad, world.getServerSentBlockStateIdAt(PAD));

            // Still within reach of the pad's cell, the boat only loses speed to the water.
            BedrockMovementState after = frame(player, boat, 184508);
            assertEquals(0.81F, (float) (after.velocity().z() / reached.velocity().z()), 0.01F);

            // The server's next update of the block gives the pad back to the client, which breaks it again.
            world.handleServerBlockUpdate(PAD, lilyPad, 0);
            assertEquals(lilyPad, world.getBlockStateIdAt(PAD));
            BedrockMovementState again = frame(player, boat, 184509);
            assertEquals(0.405F, (float) (again.velocity().z() / after.velocity().z()), 0.01F);
            assertEquals(air, world.getBlockStateIdAt(PAD));
            assertEquals(lilyPad, world.getServerSentBlockStateIdAt(PAD));

            // So does an update that removes it on the server.
            world.handleServerBlockUpdate(PAD, air, 0);
            assertEquals(air, world.getServerSentBlockStateIdAt(PAD));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    /** The velocity the same first tick ends with when no pad is in the world. */
    private static Vec3d openWaterVelocity() {
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            return frame(player, ride(player, false), 184507).velocity();
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    /** One client tick of the ridden boat, reported where the engine itself predicts it. */
    private static BedrockMovementState frame(CultPlayer player, PacketEntity boat, long tick) {
        BedrockMovementState before = state(boat);
        var frame = BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(944)
                .clientTick(tick)
                .position(new Vec3(
                        before.physicalFeetPosition().x(),
                        before.physicalFeetPosition().y(),
                        before.physicalFeetPosition().z()))
                .rotation(0, 0, 0)
                .moveVector(0, 0)
                .predictedVehicleId(RUNTIME_ID)
                .predictedVehicleJavaId(BOAT)
                .vehicleRotation(new BedrockAuthInputFrame.VehicleRotation(0, 0))
                .rawInputFlags(1L << PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE.ordinal())
                .build();
        player.bedrockState.offerAuthInputFrame(frame);
        assertNotNull(
                "tick " + tick,
                player.checkManager
                        .getSimulationProcessor()
                        .processBedrockAuthInputFrame(frame, BedrockPredictionTrigger.OFFLINE_REPLAY));
        return state(boat);
    }

    private static BedrockMovementState state(PacketEntity boat) {
        return BedrockProfileState.previousState(boat.bedrockPrediction.commit().carry());
    }

    /** Puts the player in a boat on water three blocks deep, one tick short of the pad's reach. */
    private static PacketEntity ride(CultPlayer player, boolean pad) {
        var world = player.compensatedWorld;
        for (int x = 14; x <= 21; x++) {
            for (int z = 66; z <= 76; z++) {
                world.ensureValidationChunkLoaded(x >> 4, z >> 4);
                for (int y = 117; y <= 119; y++) world.updateBlock(x, y, z, state("minecraft:water"));
            }
        }
        if (pad) world.updateBlock(PAD.getX(), PAD.getY(), PAD.getZ(), state("minecraft:lily_pad"));
        player.bedrockState.offerAuthInputFrame(BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(944)
                .build());
        Vec3 position = new Vec3(START.x(), START.y(), START.z());
        player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, position, 0.0F, 0.0F, 0);
        player.compensatedEntities.serverPositionsMap.put(
                BOAT,
                new TrackerData(
                        position.x,
                        position.y,
                        position.z,
                        0.0F,
                        0.0F,
                        EntityTypeIds.OAK_BOAT,
                        player.lastTransactionSent.get()));
        PacketEntity boat = player.compensatedEntities.getEntity(BOAT);
        boat.bedrockBoat = BedrockBoatProperties.initial(RUNTIME_ID);
        BedrockVehiclePredictionState.initializeBoat(boat, START, VELOCITY, 90.0F, BedrockCoordinateFrame.IDENTITY);
        player.compensatedEntities.vehicles.setServerVehicle(
                BOAT, new int[] {player.entityID}, player.lastTransactionSent.get());
        player.compensatedEntities.vehicles.applyVehiclePassengers(BOAT, new int[] {player.entityID});
        player.bedrockState.movementCorrections.clear();
        assertSame(boat, BedrockVehicleControl.controlledVehicle(player));
        return boat;
    }

    private static int state(String block) {
        return DataTables.defaults().registry().block(block).defaultState();
    }
}
