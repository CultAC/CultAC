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
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.BlockMovementSlowdownState;
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
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision.BlockContactBehavior;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import ac.cult.cultac.utils.collisions.BedrockClientBlockShapeMappings;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * A block slowdown clears the velocity the actor carries into its scaled move, so the slime under powder snow sees no
 * fall to rebound. Walking through powder snow on slime the client collides with the floor every tick and reports
 * (0 - 0.08) * 0.98 = -0.0784.
 */
public final class BedrockSlowdownOverSlimeTest {
    private static final double RESTING_Y = -0.0784D;

    @Test
    public void walkingThroughPowderSnowOnSlimeNeverBounces() {
        BedrockMovementContext world = powderSnowOnSlime();
        BedrockMovementState state = BedrockMovementState.fromPhysicalFeet(
                        new Vec3d(0.5D, 1.0D, 0.3D),
                        new Vec3d(0.0D, RESTING_Y, 0.0D),
                        walk(1L),
                        BedrockCollisionFlags.ON_GROUND)
                .withPendingBlockMovementSlowdownState(BlockMovementSlowdownState.POWDER_SNOW);
        for (long tick = 2L; tick <= 6L; tick++) {
            BedrockMovementResult result = tick(state, walk(tick), world);
            assertEquals(1.0D, result.predictedPosition().y(), 0.0D);
            assertTrue(result.predictedState().collisionFlags().verticalCollision());
            assertFalse(result.standingBounceBounced());
            assertEquals(RESTING_Y, result.predictedVelocity().y(), 1.0E-7D);
            state = result.predictedState();
        }
    }

    private static BedrockInputFrame walk(long tick) {
        return new BedrockInputFrame(tick, 0.0F, 0.0F, false, false, false, Set.of("UP"));
    }

    private static BedrockMovementResult tick(
            BedrockMovementState state, BedrockInputFrame frame, BedrockMovementContext world) {
        return BedrockForwardTick.simulate(new BedrockSimulation.Input(
                        state,
                        frame,
                        frame.intent(),
                        BedrockWorldSnapshot.fromContext(world),
                        true,
                        BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                        BedrockMobJumpComponentState.DEFAULT,
                        true,
                        false,
                        new Vec3d(0.0D, 0.0D, 1.0D)))
                .getFirst()
                .movementResult();
    }

    /** Slime blocks at y 0 under a powder snow block at 0 1 0, which a player without leather boots walks through. */
    private static BedrockMovementContext powderSnowOnSlime() {
        var builder = new BedrockCollisionWorldBuilder(
                BedrockClientBlockShapeMappings.catalog(), BedrockBlockShapeContext.NONE);
        int powderSnow = DataTables.defaults().registry().block("minecraft:powder_snow").defaultState();
        List<PlacedBlockCollision> blocks = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 2; z++) {
                blocks.add(PlacedBlockCollision.manual(
                        new BlockPosition(x, 0, z),
                        "minecraft:slime_block",
                        "minecraft:slime",
                        List.of(new WorldCollisionBox(x, 0.0D, z, x + 1.0D, 1.0D, z + 1.0D)),
                        Set.of(BlockContactBehavior.SLIME)));
            }
        }
        blocks.add(builder.block(
                new BlockPosition(0, 1, 0),
                powderSnow,
                BedrockCollisionShapeQuery.actor(
                        new WorldCollisionBox(0.2D, 1.0D, 0.0D, 0.8D, 2.8D, 0.6D), false, false, 0.0F)));
        return new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(blocks)),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT);
    }
}
