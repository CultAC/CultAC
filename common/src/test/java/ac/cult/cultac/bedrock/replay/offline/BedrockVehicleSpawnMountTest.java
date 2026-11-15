package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.integration.BedrockProfileState;
import ac.cult.cultac.bedrock.prediction.integration.BedrockVehiclePredictionState;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockForwardTick;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockBoatProperties;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.BubbleColumnState;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidCurrentState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.bedrock.protocol.BedrockProtocolVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The server spawns an oak boat on water flowing along +z with its feet at y 119.5, moves it to 119.50191 and seats the
 * player in it, and the client handles all of it before one tick, its first predicting the boat. A client before
 * 1.26.60 starts that tick where the boat spawned, from rest: the flow moves it 0.01134 and it ends at the same height
 * with a velocity of (0, 0.016875, 0.01134). A later client takes the lerp step toward the move instead.
 */
class BedrockVehicleSpawnMountTest {
    private static final int BOAT = 72;
    private static final int PROTOCOL_1_26_52 = 2193;
    private static final double X = 12.5D;
    // The boat's feet; the client is sent its origin, 0.375 higher.
    private static final double Y = 119.5D;
    private static final double Z = 9.5D;

    @Test
    void aBoatMountedBeforeItsFirstTickStartsAtItsSpawnAndTheFlowPushesItFromRest() {
        withPlayer(PROTOCOL_1_26_52, player -> {
            PacketEntity boat = spawnMoveAndMount(player);

            assertFalse(BedrockVehiclePredictionState.beginClientPrediction(player, boat));

            BedrockMovementState start = BedrockProfileState.previousState(
                    boat.bedrockPrediction.commit().carry());
            assertEquals(new Vec3d(X, Y, Z), start.physicalFeetPosition());
            assertEquals(Vec3d.ZERO, start.velocity());
            assertEquals(BedrockMovementState.TickStart.MOVE, start.tickStart());

            BedrockMovementState next = forward(start);
            assertEquals(0.01134F, (float) (next.physicalFeetPosition().z() - Z), 1.0E-6F);
            assertEquals(Y, next.physicalFeetPosition().y(), 1.0E-6D);
            assertEquals(0.0D, next.velocity().x(), 1.0E-9D);
            assertEquals(0.016875F, (float) next.velocity().y(), 1.0E-6F);
            assertEquals(0.01134F, (float) next.velocity().z(), 1.0E-6F);
        });
    }

    @Test
    void aBoatMountedOnALaterClientStillTakesTheLerpStepTowardTheMove() {
        withPlayer(BedrockProtocolVersion.V1_26_60.protocol(), player -> {
            PacketEntity boat = spawnMoveAndMount(player);

            assertTrue(BedrockVehiclePredictionState.beginClientPrediction(player, boat));

            BedrockMovementState start = BedrockProfileState.previousState(
                    boat.bedrockPrediction.commit().carry());
            assertEquals(119.500637F, (float) start.physicalFeetPosition().y(), 1.0E-6F);
        });
    }

    private static PacketEntity spawnMoveAndMount(CultPlayer player) {
        player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, new Vec3(X, Y, Z), 0.0F, 0.0F, 0);
        PacketEntity boat = player.compensatedEntities.getEntity(BOAT);
        boat.bedrockBoat = BedrockBoatProperties.initial(1199);
        BedrockVehiclePredictionState.initializeBoat(
                boat, new Vec3d(X, Y, Z), Vec3d.ZERO, 90.0F, BedrockCoordinateFrame.IDENTITY);
        boat.handleBedrockMove(player, new Vec3(X, 119.87691F - 0.375F, Z), 0.0F, 0.0F, false, false, true);
        player.compensatedEntities.vehicles.setServerVehicle(
                BOAT, new int[] {player.entityID}, player.lastTransactionSent.get());
        player.compensatedEntities.vehicles.applyVehiclePassengers(BOAT, new int[] {player.entityID});
        player.bedrockState.predictedVehicle = boat;
        return boat;
    }

    private static BedrockMovementState forward(BedrockMovementState state) {
        var input = new BedrockInputFrame(5045, 0.0F, 0.0F, false, false, false, Set.of());
        var request = new BedrockSimulation.Input(
                state,
                input,
                input.intent(),
                flowingWater(),
                true,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                BedrockMobJumpComponentState.DEFAULT,
                true,
                false,
                Vec3d.ZERO);
        var movement = BedrockForwardTick.simulate(request).getFirst().movementResult();
        return BedrockForwardTick.finish(movement, movement.predictedState());
    }

    /** Water two blocks deep over stone, whose current pushes 0.014 a tick along +z. */
    private static BedrockWorldSnapshot flowingWater() {
        var blocks = new ArrayList<PlacedBlockCollision>();
        blocks.add(PlacedBlockCollision.manual(
                new BlockPosition(8, 117, 4),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(8, 117, 4, 17, 118, 16))));
        for (int x = 8; x <= 16; x++) {
            for (int z = 4; z <= 15; z++) {
                blocks.add(PlacedBlockCollision.manual(
                        new BlockPosition(x, 118, z), "minecraft:water[level=0]", "minecraft:water", List.of()));
                blocks.add(PlacedBlockCollision.manual(
                        new BlockPosition(x, 119, z), "minecraft:water[level=0]", "minecraft:water", List.of()));
            }
        }
        var current = new FluidCurrentState(
                0.014D, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0.0D, 0.0D, 1.0D);
        var fluid =
                new FluidState(true, false, false, true, false, false, false, current, BubbleColumnState.NONE, 1.0D);
        return BedrockWorldSnapshot.fromContext(new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.WATER, fluid, new BlockCollisionWorld(blocks)),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT));
    }

    private static void withPlayer(int protocol, Consumer<CultPlayer> test) {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.bedrockState.offerAuthInputFrame(BedrockAuthInputFrame.builder(player.playerUUID)
                    .protocolVersion(protocol)
                    .build());
            test.accept(player);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }
}
