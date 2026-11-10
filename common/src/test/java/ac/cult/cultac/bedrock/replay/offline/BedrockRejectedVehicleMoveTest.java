package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.integration.BedrockNextTickStates;
import ac.cult.cultac.bedrock.prediction.integration.BedrockProfileState;
import ac.cult.cultac.bedrock.prediction.integration.BedrockVectorAdapter;
import ac.cult.cultac.bedrock.prediction.integration.BedrockVehiclePredictionState;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockHorseState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.checks.impl.bedrock.BedrockMovement;
import ac.cult.cultac.checks.impl.prediction.PredictionCommit;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.manager.player.SetbackFixtures;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveVehicle;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.SetbackPosWithVector;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * The server keeps a ridden horse where it was when it rejects a move, and answers with that position. A capture of a
 * horse walked against a wall shows the server answer every move with (25.502745, 120, 33.301758) while the engine
 * predicted the horse at (25.502998, 120, 33.855717) moving 0.112100765 a tick.
 */
public final class BedrockRejectedVehicleMoveTest {
    private static final int HORSE = 71;
    private static final Vec3 PREDICTED = new Vec3(25.502998F, 120.0F, 33.855717F);
    private static final Vec3 VELOCITY = new Vec3(2.3739574E-5F, -0.0784F, 0.112100765F);
    private static final Vec3 SERVER = new Vec3(25.502745F, 120.0F, 33.301758F);
    private static final float YAW = -0.0055084233F;

    @Test
    public void serverPositionReplacesOnlyThePredictedPositionAndIsNotRelayed() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            PacketEntityHorse horse = ride(player);
            var corrections = player.bedrockState.movementCorrections;
            long generation = corrections.generation();

            // The rotation is the rejected move's own: Geyser sends the vehicle's X as its pitch.
            var answer = answer(player, SERVER, 12.0F, 25.502998F);

            assertTrue(answer.isCancelled());
            assertTrue(corrections.generation() > generation);
            assertTrue(corrections.hasPendingCorrection());
            var state = BedrockProfileState.previousState(horse.bedrockPrediction.commit().carry());
            assertEquals(SERVER, BedrockVectorAdapter.toJava(state.physicalFeetPosition()));
            assertEquals(SERVER, horse.clientPhysicalPosition);
            assertEquals(VELOCITY, BedrockVectorAdapter.toJava(state.velocity()));
            assertEquals(YAW, state.inputFrame().yaw(), 0.0F);
            assertEquals(0.0F, state.inputFrame().pitch(), 0.0F);
            assertTrue(state.collisionFlags().onGround());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void serverPositionOpensNoVehicleTeleportWindow() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            ride(player);
            // Past the ticks the mount itself goes unjudged for.
            player.packetStateData.acceptedClientTick += 5;
            var check = player.checkManager.getListener(BedrockMovement.class);
            assertTrue(check.canFlagMovement());

            answer(player, SERVER, YAW, 0.0F);
            // Movement goes unjudged only while the correction itself is outstanding.
            assertFalse(check.canFlagMovement());
            player.bedrockState.movementCorrections.clear();
            assertTrue(check.canFlagMovement());

            // A teleport of the vehicle does open one.
            player.bedrockState.movementCorrections.request(player, HORSE, SERVER, Vec3.ZERO, YAW, 0.0F, true, 0);
            player.bedrockState.movementCorrections.clear();
            assertFalse(check.canFlagMovement());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void repeatedAnswerPlacesTheHorseAgainOnlyAfterAFrameMovedIt() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            PacketEntityHorse horse = ride(player);
            var corrections = player.bedrockState.movementCorrections;
            answer(player, SERVER, YAW, 0.0F);
            long placed = corrections.generation();

            // An answer to a move sent before the horse was placed is still cancelled, and changes nothing.
            assertTrue(answer(player, SERVER, YAW, 0.0F).isCancelled());
            assertEquals(placed, corrections.generation());

            // The server moved the horse itself before it answered the earlier move.
            Vec3 moved = new Vec3(25.502745F, 120.0F, 32.952637F);
            answer(player, moved, YAW, 0.0F);
            long movedGeneration = corrections.generation();
            assertTrue(movedGeneration > placed);
            assertEquals(moved, horse.clientPhysicalPosition);

            // Once a frame has moved the horse from the position, the same answer rejects that frame's move.
            predict(horse, new Vec3(25.502745F, 120.0F, 33.064737F));
            corrections.record(player, frame(player, 20629), horse, null, horse.bedrockPrediction.commit());
            assertEquals(movedGeneration, corrections.generation());
            answer(player, moved, YAW, 0.0F);
            assertTrue(corrections.generation() > movedGeneration);
            assertEquals(moved, horse.clientPhysicalPosition);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void pendingSetbackPlacesTheHorseInsteadOfTheAnswer() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            PacketEntityHorse horse = ride(player);
            var teleports = player.getSetbackTeleportUtil();
            SetbackFixtures.setLoaded(teleports, true);
            SetbackFixtures.seedSafePosition(teleports, new SetbackPosWithVector(PREDICTED, Vec3.ZERO));
            teleports.executeNonSimulatingSetback();
            assertTrue(teleports.isPendingSetback());
            long generation = player.bedrockState.movementCorrections.generation();
            Vec3 position = horse.clientPhysicalPosition;

            assertTrue(answer(player, SERVER, YAW, 0.0F).isCancelled());

            assertEquals(generation, player.bedrockState.movementCorrections.generation());
            assertEquals(position, horse.clientPhysicalPosition);
            assertTrue(teleports.isPendingSetback());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void answerForAVehicleTheClientDoesNotPredictIsRelayed() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            PacketEntityHorse horse = ride(player);
            horse.hasSaddle = false;
            long generation = player.bedrockState.movementCorrections.generation();

            assertFalse(answer(player, SERVER, YAW, 0.0F).isCancelled());

            assertEquals(generation, player.bedrockState.movementCorrections.generation());
            assertEquals(PREDICTED, horse.clientPhysicalPosition);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static PacketSendEvent<ClientboundMoveVehicle> answer(
            CultPlayer player, Vec3 position, float yaw, float pitch) {
        var packet = new ClientboundMoveVehicle(new Vec3d(position.x, position.y, position.z), yaw, pitch);
        var event =
                new PacketSendEvent<>(player.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_VEHICLE, packet, false);
        new PacketServerTeleport().onMoveVehicle(event, player, packet);
        return event;
    }

    private static PacketEntityHorse ride(CultPlayer player) {
        player.bedrockState.offerAuthInputFrame(BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(944)
                .build());
        player.compensatedEntities.addEntity(HORSE, EntityTypeIds.HORSE, PREDICTED, 0.0F, 0.0F, 0);
        var horse = (PacketEntityHorse) player.compensatedEntities.getEntity(HORSE);
        horse.hasSaddle = true;
        player.compensatedEntities.vehicles.setServerVehicle(
                HORSE, new int[] {player.entityID}, player.lastTransactionSent.get());
        player.compensatedEntities.vehicles.applyVehiclePassengers(HORSE, new int[] {player.entityID});
        predict(horse, PREDICTED);
        return horse;
    }

    private static void predict(PacketEntityHorse horse, Vec3 feet) {
        var state = BedrockMovementState.fromPhysicalFeet(
                        BedrockVectorAdapter.toBedrock(feet),
                        BedrockVectorAdapter.toBedrock(VELOCITY),
                        BedrockInputFrame.idle(0),
                        BedrockCollisionFlags.ON_GROUND,
                        Medium.GROUND,
                        BedrockCoordinateFrame.IDENTITY,
                        BedrockHorseState.initial(horse))
                .withPlayerDimensions(new PlayerDimensionsState(1.3965F, 1.6F), false)
                .withRotation(YAW, 0.0F);
        var entries = List.of(new BedrockProfileState.Entry(state, BedrockMobJumpComponentState.DEFAULT));
        horse.bedrockPrediction = new BedrockVehiclePredictionState(
                new PredictionCommit(new BedrockNextTickStates(entries), Set.of(VELOCITY)), null);
        BedrockVehiclePredictionState.commitTransform(horse, horse.bedrockPrediction.commit().carry());
    }

    private static BedrockAuthInputFrame frame(CultPlayer player, long tick) {
        return BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(944)
                .clientTick(tick)
                .predictedVehicleId((long) HORSE)
                .predictedVehicleJavaId(HORSE)
                .build();
    }
}
