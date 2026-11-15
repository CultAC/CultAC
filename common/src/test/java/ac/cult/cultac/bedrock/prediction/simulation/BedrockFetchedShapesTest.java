package ac.cult.cultac.bedrock.prediction.simulation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.prediction.api.BedrockBlockShapeContext;
import ac.cult.cultac.bedrock.prediction.api.BedrockCollisionShapeQuery;
import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockCollisionWorldBuilder;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.utils.collisions.BedrockClientBlockShapeMappings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * A move collides only with the block shapes its fetch box overlaps. Walking east along the south face of a waterlogged
 * north-facing stair's upper step, with the box's north face exactly on the step's south face, a rounding-sized
 * northward request does not reach the step: the client keeps it, reports no horizontal collision and does not climb
 * out of the water. A real northward move still fetches the step, which stops it and steps it up.
 */
public final class BedrockFetchedShapesTest {
    private static final BedrockCoordinateFrame FRAME = new BedrockCoordinateFrame(20112, 20304, 1);
    private static final BlockPosition STAIR = new BlockPosition(20133, 120, 20324);
    private static final Vec3d FEET = new Vec3d(20112 + (double) 21.164076F, 120.5D, 20304 + (double) 20.8F);

    @Test
    public void aTouchedStepOutsideTheFetchBoxDoesNotStopARoundingMove() {
        // Captured tick: yaw -90.00001, previous velocity (0.12095789, -0.005, 3.0530085E-9); the client moves to
        // x 21.304634 and reports (0.11244631, -0.005, -1.1218955E-10) with no horizontal collision.
        BedrockMovementResult result = walk(-90.00001F, new Vec3d(0.12095789D, -0.005D, 3.0530085E-9D));
        assertTrue(result.selectedWaterTravel());
        assertEquals(21.304634D, result.predictedPosition().x() - FRAME.originX(), 1.0E-6D);
        assertEquals(120.5D, result.predictedPosition().y(), 0.0D);
        assertEquals(FEET.z(), result.predictedPosition().z(), 0.0D);
        assertEquals(0.11244631D, result.predictedVelocity().x(), 1.0E-7D);
        assertEquals(-0.005D, result.predictedVelocity().y(), 1.0E-7D);
        assertEquals(-1.1218955E-10D, result.predictedVelocity().z(), 1.0E-12D);
        BedrockCollisionFlags flags = result.predictedState().collisionFlags();
        assertFalse(flags.horizontalCollision());
        assertFalse(flags.liquidClimbOut());
    }

    @Test
    public void aMoveIntoTheStepStillFetchesIt() {
        BedrockMovementResult result = walk(-90.0F, new Vec3d(0.12095789D, -0.005D, -0.05D));
        // The step stops the move on its south face and the move steps up onto it.
        assertEquals(121.0D, result.predictedPosition().y(), 0.0D);
        assertTrue(result.predictedPosition().z() < FEET.z());
    }

    private static BedrockMovementResult walk(float yaw, Vec3d velocity) {
        var builder = new BedrockCollisionWorldBuilder(
                BedrockClientBlockShapeMappings.catalog(), BedrockBlockShapeContext.NONE);
        var registry = DataTables.defaults().registry();
        int stair = registry.block("minecraft:oak_stairs").defaultState();
        stair = registry.with(stair, "facing", "north");
        stair = registry.with(stair, "shape", "straight");
        stair = registry.with(stair, "half", "bottom");
        stair = registry.with(stair, "waterlogged", "true");
        int stone = registry.block("minecraft:stone").defaultState();
        List<PlacedBlockCollision> blocks = new ArrayList<>();
        for (int x = STAIR.x() - 3; x <= STAIR.x() + 3; x++)
            for (int z = STAIR.z() - 2; z <= STAIR.z() + 2; z++)
                blocks.add(builder.block(new BlockPosition(x, 119, z), stone, BedrockCollisionShapeQuery.NONE));
        blocks.add(builder.block(STAIR, stair, BedrockCollisionShapeQuery.NONE));
        blocks.add(PlacedBlockCollision.manual(
                STAIR, "minecraft:water[level=0]", "minecraft:water", Map.of("liquid_depth", 0), List.of()));
        var world = new BlockCollisionWorld(blocks).withCoordinateFrame(FRAME);
        var context = new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.WATER, FluidState.NONE, world),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT);
        var previous = BedrockMovementState.fromPhysicalFeet(
                        FEET,
                        velocity,
                        new BedrockInputFrame(1L, yaw, 0.0F, false, false, false, Set.of("UP")),
                        BedrockCollisionFlags.ON_GROUND,
                        Medium.WATER,
                        FRAME)
                .withWaterTravelFlag(true);
        var input = new BedrockInputFrame(2L, yaw, 0.0F, false, false, false, Set.of("UP"));
        return BedrockForwardTick.simulate(new BedrockSimulation.Input(
                        previous,
                        input,
                        input.intent(),
                        BedrockWorldSnapshot.fromContext(context),
                        true,
                        BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                        BedrockMobJumpComponentState.DEFAULT,
                        true,
                        false,
                        new Vec3d(0.0D, 0.0D, 1.0D)))
                .getFirst()
                .movementResult();
    }
}
