package ac.cult.cultac.bedrock.prediction.simulation;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockBoundingBoxMode;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockTravelInput;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BedrockGlideWaterTransitionTest {
    private static final Vec3d START = new Vec3d(-175.45814514160156D, 62.62706756591797D, 82.38558959960938D);
    private static final Vec3d CARRY = new Vec3d(-0.47418212890625D, -0.042384033203125D, -0.217333984375D);
    private static final Vec3d OBSERVED = new Vec3d(-175.93243408203125D, 62.57141876220703D, 82.16767883300781D);
    private static final BedrockInputFrame FRAME = new BedrockInputFrame(
        2573L, 115.10004F, 5.9234467F, true, false, false,
        Set.of("GLIDING", "ACTOR_POSE_SNAPSHOT", "JUMPING", "JUMP_CURRENT_RAW", "WANT_UP"));

    @Test
    public void acknowledgedStandingBoxSurvivesGlidePoseUpdate() {
        // Flag 3 (2026-09-15): HEIGHT=1.8 at sequence 75095 and GLIDING=true
        // at 75127 were both acknowledged before auth 2573 (sequence 75188).
        // The test seeds that logged state over a flat, source-water surface.
        BedrockMovementState state = glidingState(1.8F);
        BedrockMovementResult result = move(state, FRAME);

        assertTrue(result.selectedGlidingTravel());
        assertFalse(result.selectedWaterTravel());
        assertEquals(OBSERVED.x(), result.predictedPosition().x(), 0.00001D);
        assertEquals(OBSERVED.y(), result.predictedPosition().y(), 0.00001D);
        assertEquals(OBSERVED.z(), result.predictedPosition().z(), 0.00001D);
        // Gliding alone does not replace the acknowledged standing dimensions.
        assertEquals(1.8F, result.predictedState().playerDimensions().height(), 0.0D);
        assertEquals("Repeated prediction must start from the same state", result, move(state, FRAME));
    }

    @Test
    public void carriedHorizontalBoxStillSelectsWaterWhileGliding() {
        BedrockMovementResult result = move(glidingState(0.6F), FRAME);

        assertTrue(result.selectedWaterTravel());
        assertFalse(result.selectedGlidingTravel());
        assertEquals(CARRY.y() + 0.04F, result.collisionInputVelocity().y(), 0.000001D);
    }

    private static BedrockMovementState glidingState(float height) {
        return BedrockMovementState.fromPhysicalFeet(START, CARRY, BedrockInputFrame.idle(2572L),
            BedrockCollisionFlags.AIR, Medium.WATER)
            .withGliding(true)
            .withPlayerDimensions(BedrockBoundingBoxMode.HORIZONTAL,
                new PlayerDimensionsState(0.6F, height), true);
    }

    private static BedrockMovementResult move(BedrockMovementState state, BedrockInputFrame frame) {
        List<PlacedBlockCollision> blocks = new ArrayList<>();
        for (int x = -179; x <= -173; x++) {
            for (int z = 79; z <= 85; z++) {
                for (int y = 60; y <= 62; y++) {
                    blocks.add(PlacedBlockCollision.manual(new BlockPosition(x, y, z),
                        "minecraft:water[level=0]", "minecraft:water", Map.of("liquid_depth", 0), List.of()));
                }
            }
        }
        BedrockMovementContext context = new BedrockMovementContext(
            BedrockEffectState.NONE, AttributeState.DEFAULT,
            new WorldContactState(Medium.WATER, FluidState.NONE, new BlockCollisionWorld(blocks)),
            new EquipmentState(0, 0, 0, 1, false, true), EntityContactState.NONE,
            new MovementModifierState(true, true, false, false, 0.04999750480055809D,
                false, true, false, false, 0.35D, 0L), PlayerDimensionsState.DEFAULT);
        return BedrockSimulation.move(state, frame, context,
            BedrockTravelInput.ScaffoldingVerticalBranch.SOURCE, false, BedrockSimulation.DEFAULT_MAX_AUTO_STEP);
    }
}
