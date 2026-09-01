package ac.grim.grimac.bedrock.prediction.input;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class BedrockInputIntentTest {
    @Test
    public void currentSprintingStateWinsOverStopActionEdge() {
        BedrockInputIntent.SprintIntent intent = new BedrockInputIntent.SprintIntent(
            true,
            false,
            true,
            false
        );

        assertTrue(intent.currentTickActorSprinting(false));
        assertTrue(intent.nextActorSprinting(false));
    }

    @Test
    public void stopEdgeClearsPersistedSprintWithoutCurrentState() {
        BedrockInputIntent.SprintIntent intent = new BedrockInputIntent.SprintIntent(
            false,
            false,
            true,
            false
        );

        assertFalse(intent.currentTickActorSprinting(true));
        assertFalse(intent.nextActorSprinting(true));
    }

    @Test
    public void stopEdgeWinsPersistedActionOrderWhenBothEdgesArePresent() {
        BedrockInputIntent.SprintIntent intent = new BedrockInputIntent.SprintIntent(
            true,
            true,
            true,
            false
        );

        assertFalse(intent.afterActionEdges(true));
    }
}
