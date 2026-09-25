package ac.cult.cultac.bedrock.prediction.simulation;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
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
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class BedrockGlideStairStepTest {
    @Test
    public void glidingLandingStepsUpEastFacingStairAndKeepsHorizontalVelocity() {
        // Flag 89, auth tick 26511: the client glided onto the upper half of this stair.
        Vec3d start = new Vec3d(268.13134765625D, 82.55297088623047D, -90.61742401123047D);
        Vec3d carriedVelocity = new Vec3d(
            0.2485734522342682D, -0.2382630705833435D, 0.09112013876438141D);
        PlayerDimensionsState glideSize = new PlayerDimensionsState(0.6F, 0.6F);
        BedrockMovementState previous = BedrockMovementState.fromPhysicalFeet(
                start, carriedVelocity, BedrockInputFrame.idle(26504L), BedrockCollisionFlags.AIR)
            .withGliding(true)
            .withPlayerDimensions(BedrockBoundingBoxMode.HORIZONTAL, glideSize, true);
        BedrockInputFrame frame = new BedrockInputFrame(
            26505L, -86.39594F, 25.826218F, false, false, true,
            Set.of("SPRINTING", "SPRINT_DOWN", "GLIDING", "ACTOR_POSE_SNAPSHOT"));
        PlacedBlockCollision stair = PlacedBlockCollision.manual(
            new BlockPosition(268, 82, -91),
            "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]",
            "minecraft:oak_stairs",
            List.of(
                new WorldCollisionBox(268.0D, 82.0D, -91.0D, 269.0D, 82.5D, -90.0D),
                new WorldCollisionBox(268.5D, 82.5D, -91.0D, 269.0D, 83.0D, -90.0D)));
        BedrockMovementContext context = new BedrockMovementContext(
            BedrockEffectState.NONE, AttributeState.DEFAULT,
            new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(List.of(stair))),
            EquipmentState.NONE, EntityContactState.NONE, MovementModifierState.NONE, glideSize);

        BedrockMovementResult result = BedrockSimulation.move(previous, frame, context,
            BedrockTravelInput.ScaffoldingVerticalBranch.SOURCE, true,
            BedrockSimulation.DEFAULT_MAX_AUTO_STEP);

        assertTrue(result.selectedGlidingTravel());
        assertTrue(result.steppedUp());
        assertEquals(268.3984069824219D, result.predictedPosition().x(), 0.001D);
        assertEquals(83.0D, result.predictedPosition().y(), 0.001D);
        assertEquals(-90.53336334228516D, result.predictedPosition().z(), 0.001D);
        assertEquals(0.26706567D, result.predictedState().velocity().x(), 0.001D);
        assertEquals(0.0D, result.predictedState().velocity().y(), 0.001D);
        assertEquals(0.08405893D, result.predictedState().velocity().z(), 0.001D);
    }
}
