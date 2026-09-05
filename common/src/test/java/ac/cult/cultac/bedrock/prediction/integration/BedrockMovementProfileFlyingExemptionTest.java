package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BedrockMovementProfileFlyingExemptionTest {
    @Test
    public void startFlyingExemptsTheMovementFromTheSameAuthInputFrame() {
        assertTrue(BedrockMovementProfile.startsFlyingExemptionThisFrame(
                true, false, frameWith(PlayerAuthInputData.START_FLYING)));
    }

    @Test
    public void startFlyingStillRequiresTheGrantedAbility() {
        assertFalse(BedrockMovementProfile.startsFlyingExemptionThisFrame(
                false, false, frameWith(PlayerAuthInputData.START_FLYING)));
    }

    @Test
    public void rejectedSwimmingStartDoesNotExemptMovement() {
        assertFalse(BedrockMovementProfile.startsFlyingExemptionThisFrame(
                true, true, frameWith(PlayerAuthInputData.START_FLYING)));
    }

    @Test
    public void stopFlyingWinsWhenBothActionsArePresent() {
        assertFalse(BedrockMovementProfile.startsFlyingExemptionThisFrame(
                true, false, frameWith(PlayerAuthInputData.START_FLYING, PlayerAuthInputData.STOP_FLYING)));
    }

    private static BedrockAuthInputFrame frameWith(PlayerAuthInputData... inputs) {
        long low = 0L;
        long high = 0L;
        for (PlayerAuthInputData input : inputs) {
            int ordinal = input.ordinal();
            if (ordinal < Long.SIZE) {
                low |= 1L << ordinal;
            } else {
                high |= 1L << (ordinal - Long.SIZE);
            }
        }
        return BedrockAuthInputFrame.builder(UUID.randomUUID())
                .rawInputFlags(low)
                .rawInputFlagsHigh(high)
                .build();
    }
}
