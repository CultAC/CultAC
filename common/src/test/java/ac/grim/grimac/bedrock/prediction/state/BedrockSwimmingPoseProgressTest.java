package ac.grim.grimac.bedrock.prediction.state;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class BedrockSwimmingPoseProgressTest {
    @Test
    public void decaysToZeroWithBedrockFloatStepSemantics() {
        double swimAmount = 1.0D;

        for (int tick = 0; tick < 10; tick++) {
            swimAmount = BedrockSwimmingPoseProgress.nextSwimAmount(swimAmount, false);
        }

        assertEquals(0.0D, swimAmount, 0.0D);
    }
}
