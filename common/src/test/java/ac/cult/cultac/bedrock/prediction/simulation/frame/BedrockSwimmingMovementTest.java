package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputIntent;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public final class BedrockSwimmingMovementTest {
    @Test
    public void retainedDryStartNeedsWaterBeforeItCanBecomeActorSwimming() {
        var initial = new BedrockSwimmingMovement.SwimmingState(false, false, 0.0D);
        var retained = new BedrockInputFrame(2L, 0.0F, 0.0F, false, false, false, Set.of(), true).intent();
        assertFalse(initial.afterActions(intent("START_SWIMMING"), false).actorStateAfterActions());
        assertFalse(initial.afterActions(retained, false).actorStateAfterActions());
        assertTrue(initial.afterActions(retained, true).actorStateAfterActions());
        assertFalse(initial.afterActions(intent("STOP_SWIMMING"), true).actorStateAfterActions());
        assertFalse(initial.afterActions(intent(), true).actorStateAfterActions());
    }

    @Test
    public void swimmingPoseAndAnimationDoNotBecomeARequest() {
        var swimmingLooking = new BedrockSwimmingMovement.SwimmingState(false, false, 1.0D);
        assertFalse(swimmingLooking.afterActions(intent("SWIMMING", "HORIZONTAL_POSE"), true).actorStateAfterActions());
    }

    @Test
    public void startSwimmingActionSetsActorFlag() {
        BedrockSwimmingMovement.SwimmingState initial =
            new BedrockSwimmingMovement.SwimmingState(false, false, 0.0D);

        BedrockSwimmingMovement.SwimmingState result =
            initial.afterActions(intent("START_SWIMMING"), true);

        assertTrue(result.actorStateAfterActions());
    }

    @Test
    public void stopSwimmingActionWinsWhenBothBitsAreSet() {
        BedrockSwimmingMovement.SwimmingState initial =
            new BedrockSwimmingMovement.SwimmingState(true, true, (double) 0.1F);

        BedrockSwimmingMovement.SwimmingState result = initial.afterActions(intent(
            "START_SWIMMING",
            "STOP_SWIMMING"
        ), true);

        org.junit.Assert.assertFalse(result.actorStateAfterActions());
    }

    @Test
    public void currentSwimAmountIsNotRecomputedByLaterActions() {
        BedrockSwimmingMovement.SwimmingState initial =
            new BedrockSwimmingMovement.SwimmingState(true, true, (double) 0.2F);

        BedrockSwimmingMovement.SwimmingState result =
            initial.afterActions(intent("STOP_SWIMMING"), true);

        org.junit.Assert.assertEquals((double) 0.2F, result.swimAmount(), 0.0D);
    }

    private static BedrockInputIntent intent(String... inputData) {
        return BedrockInputIntent.from(new BedrockInputFrame(
            1L,
            0.0F,
            0.0F,
            false,
            false,
            false,
            Set.of(inputData)
        ));
    }
}
