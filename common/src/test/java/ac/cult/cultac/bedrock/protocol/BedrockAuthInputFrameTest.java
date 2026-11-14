package ac.cult.cultac.bedrock.protocol;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;

public final class BedrockAuthInputFrameTest {
    @Test
    public void heldJumpTheWaterJumpAndTheBlockAscendControlEachAskForAJump() {
        assertTrue(requestsJump(PlayerAuthInputData.JUMPING));
        assertTrue(requestsJump(PlayerAuthInputData.AUTO_JUMPING_IN_WATER));
        assertTrue(requestsJump(PlayerAuthInputData.ASCEND_BLOCK));
        assertTrue(requestsJump(
                PlayerAuthInputData.ASCEND_BLOCK,
                PlayerAuthInputData.JUMP_PRESSED_RAW,
                PlayerAuthInputData.JUMP_CURRENT_RAW));
    }

    @Test
    public void theJumpKeyAndTheJumpStartAloneAskForNoJump() {
        assertFalse(requestsJump());
        assertFalse(requestsJump(PlayerAuthInputData.JUMP_DOWN));
        assertFalse(requestsJump(PlayerAuthInputData.START_JUMPING));
        assertFalse(requestsJump(PlayerAuthInputData.JUMP_PRESSED_RAW, PlayerAuthInputData.JUMP_CURRENT_RAW));
        assertFalse(requestsJump(PlayerAuthInputData.WANT_UP, PlayerAuthInputData.ASCEND));
    }

    private static boolean requestsJump(PlayerAuthInputData... held) {
        Set<PlayerAuthInputData> inputData = EnumSet.noneOf(PlayerAuthInputData.class);
        inputData.addAll(Set.of(held));
        return BedrockAuthInputFrame.requestsJump(inputData::contains);
    }
}
