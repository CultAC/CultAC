package ac.cult.cultac.bedrock.protocol;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BedrockClientPoseStateTest {
    @Test
    public void onlyHorizontalPosesUseHorizontalPoseHeight() {
        assertTrue(BedrockClientPoseState.STANDING.withCrawling(true).lowHeightPose());
        assertTrue(BedrockClientPoseState.STANDING.withSwimming(true).lowHeightPose());
        assertFalse(BedrockClientPoseState.STANDING.lowHeightPose());
    }
}
