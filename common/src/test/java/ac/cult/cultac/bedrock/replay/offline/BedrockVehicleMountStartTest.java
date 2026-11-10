package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * A capture shows a boat pushed along z by the world border lerp toward one target a tick (2.49036, 2.4722838,
 * 2.446015, 2.4114335) and the player mounted with a velocity of -0.086553134 and a last move to 2.3595016 sent after
 * the link. The client's first tick in the boat still lerps it toward that last move, from 2.453423 to 2.4221158,
 * and its prediction starts there at rest.
 */
class BedrockVehicleMountStartTest {
    private static final int BOAT = 71;
    private static final double X = -5.5D;
    private static final double Y = 120.375D;

    @Test
    void theFirstPredictedTickTakesTheLerpStepAndStartsAtRest() {
        withPlayer(player -> {
            PacketEntity boat = spawnBoat(player, 2.5D);
            for (double z : new double[] {2.49036D, 2.4722838D, 2.446015D, 2.4114335D}) {
                move(player, boat, z);
                boat.onMovement(player, true);
            }
            assertEquals(2.453423F, (float) boat.clientPhysicalPosition.z, 1.0E-6F);

            mount(player);
            assertSame(boat, BedrockVehicleControl.controlledVehicle(player));
            // Until its first tick in the boat the client still handles the boat's moves.
            assertNull(BedrockVehicleControl.predictedVehicle(player));
            player.compensatedEntities.vehicles.applyClientboundVehicleVelocity(
                    boat, new Vec3(0.0D, 0.0D, -0.086553134F));
            move(player, boat, 2.3595016F);

            player.bedrockState.predictedVehicle = boat;
            BedrockVehiclePredictionState.beginClientPrediction(player, boat);

            BedrockMovementState state = BedrockProfileState.previousState(boat.bedrockPrediction.commit().carry());
            assertEquals(2.4221158F, (float) state.physicalFeetPosition().z(), 1.0E-6F);
            assertEquals(2.4221158F, (float) boat.clientPhysicalPosition.z, 1.0E-6F);
            assertEquals(Vec3.ZERO, BedrockVectorAdapter.toJava(state.velocity()));
            assertSame(boat, BedrockVehicleControl.predictedVehicle(player));

            // From then on the client drives the boat: the tick end leaves it where the prediction put it.
            player.checkManager.endBedrockClientTick();
            assertEquals(2.4221158F, (float) boat.clientPhysicalPosition.z, 1.0E-6F);
        });
    }

    @Test
    void aMountWithoutAMoveInFlightStartsWhereTheBoatIsShown() {
        withPlayer(player -> {
            PacketEntity boat = spawnBoat(player, 2.5D);
            mount(player);
            assertFalse(player.bedrockState.movementCorrections.hasPendingCorrection());

            player.bedrockState.predictedVehicle = boat;
            BedrockVehiclePredictionState.beginClientPrediction(player, boat);

            BedrockMovementState state = BedrockProfileState.previousState(boat.bedrockPrediction.commit().carry());
            assertEquals(2.5D, state.physicalFeetPosition().z(), 1.0E-9D);
            assertEquals(Vec3.ZERO, BedrockVectorAdapter.toJava(state.velocity()));
        });
    }

    private static PacketEntity spawnBoat(CultPlayer player, double z) {
        player.bedrockState.offerAuthInputFrame(BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(944)
                .build());
        player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, new Vec3(X, Y, z), 0.0F, 0.0F, 0);
        PacketEntity boat = player.compensatedEntities.getEntity(BOAT);
        boat.bedrockBoat = BedrockBoatProperties.initial(1917);
        BedrockVehiclePredictionState.initializeBoat(
                boat, new Vec3d(X, Y, z), Vec3d.ZERO, 90.0F, BedrockCoordinateFrame.IDENTITY);
        return boat;
    }

    private static void mount(CultPlayer player) {
        player.compensatedEntities.vehicles.setServerVehicle(
                BOAT, new int[] {player.entityID}, player.lastTransactionSent.get());
        player.compensatedEntities.vehicles.applyVehiclePassengers(BOAT, new int[] {player.entityID});
    }

    private static void move(CultPlayer player, PacketEntity boat, double z) {
        boat.handleBedrockMove(player, new Vec3(X, Y, z), 0.0F, 0.0F, false, false, true);
    }

    private static void withPlayer(Consumer<CultPlayer> test) {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            test.accept(player);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }
}
