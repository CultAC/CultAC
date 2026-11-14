package ac.cult.cultac.bedrock.prediction.input;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import org.junit.Test;

public final class BedrockInputIntentTest {
    @Test
    public void currentSprintingStateWinsOverStopActionEdge() {
        BedrockInputIntent.SprintIntent intent = new BedrockInputIntent.SprintIntent(true, false, true, false);

        assertTrue(intent.currentTickActorSprinting(false));
        assertTrue(intent.nextActorSprinting(false));
    }

    @Test
    public void stopEdgeClearsPersistedSprintWithoutCurrentState() {
        BedrockInputIntent.SprintIntent intent = new BedrockInputIntent.SprintIntent(false, false, true, false);

        assertFalse(intent.currentTickActorSprinting(true));
        assertFalse(intent.nextActorSprinting(true));
    }

    @Test
    public void stopEdgeWinsPersistedActionOrderWhenBothEdgesArePresent() {
        BedrockInputIntent.SprintIntent intent = new BedrockInputIntent.SprintIntent(true, true, true, false);

        assertFalse(intent.afterActionEdges(true));
    }

    // Each input mode has its own control for the descent through the block under the feet.
    @Test
    public void keyboardDescendsThroughABlockWhileSneaking() {
        assertTrue(descendsThroughBlock("SNEAKING"));
        assertTrue(descendsThroughBlock("SNEAKING", "SNEAK_DOWN", "WANT_DOWN"));
        assertFalse(descendsThroughBlock("SNEAK_DOWN", "WANT_DOWN", "DESCEND"));
        assertFalse(descendsThroughBlock("DESCEND_BLOCK"));
    }

    @Test
    public void touchDescendsThroughABlockOnlyWithItsDescendControl() {
        String touch = BedrockInputMode.TOUCH.inputData();
        assertTrue(descendsThroughBlock(touch, "DESCEND_BLOCK"));
        assertFalse(descendsThroughBlock(touch, "SNEAKING", "SNEAK_DOWN", "WANT_DOWN"));
        assertFalse(descendsThroughBlock(touch));
    }

    @Test
    public void gamepadDescendsThroughABlockWithSneakToggledAndHeld() {
        String gamepad = BedrockInputMode.GAMEPAD.inputData();
        assertTrue(descendsThroughBlock(gamepad, "SNEAK_TOGGLE_DOWN", "SNEAKING", "SNEAK_DOWN"));
        assertFalse(descendsThroughBlock(gamepad, "SNEAKING", "SNEAK_DOWN"));
        assertFalse(descendsThroughBlock(gamepad, "SNEAK_TOGGLE_DOWN", "SNEAKING"));
        assertFalse(descendsThroughBlock(gamepad, "SNEAK_TOGGLE_DOWN", "SNEAK_DOWN"));
        assertFalse(descendsThroughBlock(gamepad, "DESCEND_BLOCK"));
    }

    @Test
    public void anyOtherInputModeNeverDescendsThroughABlock() {
        String other = BedrockInputMode.OTHER.inputData();
        assertFalse(descendsThroughBlock(other, "SNEAKING", "SNEAK_DOWN", "SNEAK_TOGGLE_DOWN", "DESCEND_BLOCK"));
    }

    private static boolean descendsThroughBlock(String... inputData) {
        return new BedrockInputFrame(1L, 0.0F, 0.0F, false, false, false, Set.of(inputData))
                .intent()
                .vertical()
                .descendThroughBlock();
    }
}
