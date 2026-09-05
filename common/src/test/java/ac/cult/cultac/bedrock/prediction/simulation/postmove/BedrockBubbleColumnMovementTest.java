package ac.cult.cultac.bedrock.prediction.simulation.postmove;

import ac.cult.cultac.bedrock.prediction.world.BubbleColumnLayer;
import ac.cult.cultac.bedrock.prediction.world.BubbleColumnState;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class BedrockBubbleColumnMovementTest {
    @Test
    public void mixedBubbleColumnsRetainEachLayersDragDirection() {
        BubbleColumnState state = state(List.of(
                BubbleColumnLayer.inside(4, false, 0L, Long.MAX_VALUE),
                BubbleColumnLayer.inside(4, true, 0L, Long.MAX_VALUE)));

        assertEquals(0.03D, BedrockBubbleColumnMovement.velocityY(state, 0.0D, 4.0D, 1L), 1.0E-12D);
    }

    @Test
    public void aboveAndInsideLayersUseTheirOwnBedrockCaps() {
        BubbleColumnState upward = state(List.of(
                BubbleColumnLayer.inside(4, false, 0L, Long.MAX_VALUE),
                BubbleColumnLayer.above(5, false, 0L, Long.MAX_VALUE)));
        BubbleColumnState downward = state(List.of(
                BubbleColumnLayer.inside(4, true, 0L, Long.MAX_VALUE),
                BubbleColumnLayer.above(5, true, 0L, Long.MAX_VALUE)));

        assertEquals(0.8D, BedrockBubbleColumnMovement.velocityY(upward, 0.69D, 4.0D, 1L), 1.0E-12D);
        assertEquals(-0.33D, BedrockBubbleColumnMovement.velocityY(downward, -0.29D, 4.0D, 1L), 1.0E-12D);
    }

    private static BubbleColumnState state(List<BubbleColumnLayer> layers) {
        return new BubbleColumnState(4.0D, 5.0D, 5.0D, 6.0D, 1.8D, layers);
    }
}
