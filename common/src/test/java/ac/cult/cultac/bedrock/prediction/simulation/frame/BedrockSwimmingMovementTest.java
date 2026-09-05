package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputIntent;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public final class BedrockSwimmingMovementTest {
    @Test
    public void startSwimmingActionSetsActorFlag() {
        BedrockSwimmingMovement.SwimmingState initial =
            new BedrockSwimmingMovement.SwimmingState(false, false, 0.0D);

        BedrockSwimmingMovement.SwimmingState result =
            initial.afterActions(intent("START_SWIMMING"));

        assertTrue(result.actorStateAfterActions());
    }

    @Test
    public void stopSwimmingActionWinsWhenBothBitsAreSet() {
        BedrockSwimmingMovement.SwimmingState initial =
            new BedrockSwimmingMovement.SwimmingState(true, true, (double) 0.1F);

        BedrockSwimmingMovement.SwimmingState result = initial.afterActions(intent(
            "START_SWIMMING",
            "STOP_SWIMMING"
        ));

        org.junit.Assert.assertFalse(result.actorStateAfterActions());
    }

    @Test
    public void currentSwimAmountIsNotRecomputedByLaterActions() {
        BedrockSwimmingMovement.SwimmingState initial =
            new BedrockSwimmingMovement.SwimmingState(true, true, (double) 0.2F);

        BedrockSwimmingMovement.SwimmingState result =
            initial.afterActions(intent("STOP_SWIMMING"));

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
