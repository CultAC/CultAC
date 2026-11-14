package ac.cult.cultac.bedrock.prediction.simulation.frame;

import static org.junit.Assert.assertEquals;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import java.util.Set;
import org.junit.Test;

public final class BedrockTravelSneakScaleTest {
    private static final float SNEAK = 0.3F;

    @Test
    public void sneakButtonStillHeldAfterToggledStopDoesNotSlowTheInput() {
        // Flag 105, tick 3807: the press that toggled the sneak off is still held.
        assertEquals(1.0F, scale(true, false, "ACTOR_POSE_SNAPSHOT", "SNEAK_TOGGLE_DOWN", "SNEAK_CURRENT_RAW"), 0.0F);
    }

    @Test
    public void tickThatStopsTheSneakIsStillSlowed() {
        assertEquals(
                SNEAK,
                scale(
                        true,
                        false,
                        "ACTOR_POSE_SNAPSHOT",
                        "ACTOR_SNEAKING",
                        "STOP_SNEAKING",
                        "SNEAK_TOGGLE_DOWN",
                        "SNEAK_PRESSED_RAW",
                        "SNEAK_CURRENT_RAW"),
                0.0F);
    }

    @Test
    public void releasingAToggledSneakButtonKeepsTheSneak() {
        assertEquals(
                SNEAK,
                scale(
                        true,
                        true,
                        "ACTOR_POSE_SNAPSHOT",
                        "ACTOR_SNEAKING",
                        "SNEAKING",
                        "SNEAK_DOWN",
                        "SNEAK_RELEASED_RAW"),
                0.0F);
    }

    @Test
    public void tickThatStartsTheSneakIsAlreadySlowed() {
        assertEquals(
                SNEAK,
                scale(
                        false,
                        true,
                        "ACTOR_POSE_SNAPSHOT",
                        "START_SNEAKING",
                        "SNEAKING",
                        "SNEAK_DOWN",
                        "SNEAK_TOGGLE_DOWN",
                        "SNEAK_PRESSED_RAW",
                        "SNEAK_CURRENT_RAW"),
                0.0F);
    }

    @Test
    public void descendControlSlowsAGroundedPlayer() {
        assertEquals(SNEAK, scale(false, false, "ACTOR_POSE_SNAPSHOT", "DESCEND"), 0.0F);
    }

    private static float scale(boolean previouslySneaking, boolean sneaking, String... inputData) {
        BedrockInputFrame previousFrame =
                new BedrockInputFrame(1L, 0.0F, 0.0F, false, previouslySneaking, false, Set.of());
        BedrockMovementState previous = BedrockMovementState.fromPhysicalFeet(
                new Vec3d(0.5D, 1.0D, 0.5D),
                new Vec3d(0.03D, -0.0784D, 0.0D),
                previousFrame,
                BedrockCollisionFlags.ON_GROUND);
        BedrockInputFrame frame = new BedrockInputFrame(2L, 0.0F, 0.0F, false, sneaking, false, Set.of(inputData));
        BedrockMovementContext context = new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, BlockCollisionWorld.EMPTY),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT);
        return BedrockTravelInputControl.resolve(
                        previous,
                        frame,
                        false,
                        BedrockTravelOptions.SprintTravelSpeedMode.ORDERED_ACTOR_FLAG,
                        frame.intent(),
                        context,
                        BedrockEffectState.NONE)
                .moveInputScale();
    }
}
