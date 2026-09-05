package ac.cult.cultac.bedrock.prediction.simulation;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BedrockFallDistanceTest {
    @Test
    public void accumulatesResolvedDownwardDisplacementAsFloat() {
        float expected = 1.25F - (float) -0.452300012345D;

        assertEquals(expected, BedrockFallDistance.update(1.25F, -0.452300012345D, false, false), 0.0F);
    }

    @Test
    public void doesNotAccumulateUpwardDisplacement() {
        assertEquals(1.25F, BedrockFallDistance.update(1.25F, 0.42D, false, false), 0.0F);
    }

    @Test
    public void resetWinsOverCurrentMovement() {
        assertEquals(0.0F, BedrockFallDistance.update(4.0F, -0.5D, true, false), 0.0F);
    }

    @Test
    public void lavaHalvesTheUpdatedDistance() {
        assertEquals(1.5F, BedrockFallDistance.update(2.0F, -1.0D, false, true), 0.0F);
    }
}
