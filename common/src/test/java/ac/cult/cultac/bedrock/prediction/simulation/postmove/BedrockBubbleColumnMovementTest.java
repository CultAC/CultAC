package ac.cult.cultac.bedrock.prediction.simulation.postmove;

import static org.junit.Assert.assertEquals;

import ac.cult.cultac.bedrock.prediction.world.BubbleColumnLayer;
import ac.cult.cultac.bedrock.prediction.world.BubbleColumnState;
import java.util.List;
import org.junit.Test;

public final class BedrockBubbleColumnMovementTest {
    @Test
    public void mixedBubbleColumnsRetainEachLayersDragDirection() {
        BubbleColumnState state =
                new BubbleColumnState(List.of(BubbleColumnLayer.inside(4, false), BubbleColumnLayer.inside(4, true)));

        assertEquals(0.03D, BedrockBubbleColumnMovement.velocityY(state, 0.0D), 1.0E-12D);
    }

    @Test
    public void aboveAndInsideLayersUseTheirOwnBedrockCaps() {
        BubbleColumnState upward =
                new BubbleColumnState(List.of(BubbleColumnLayer.inside(4, false), BubbleColumnLayer.above(5, false)));
        BubbleColumnState downward =
                new BubbleColumnState(List.of(BubbleColumnLayer.inside(4, true), BubbleColumnLayer.above(5, true)));

        assertEquals(0.8D, BedrockBubbleColumnMovement.velocityY(upward, 0.69D), 1.0E-12D);
        assertEquals(-0.33D, BedrockBubbleColumnMovement.velocityY(downward, -0.29D), 1.0E-12D);
    }

    @Test
    public void boatIsPushedOnlyByTheColumnBlocksItIsSubmergedIn() {
        BubbleColumnState surface = new BubbleColumnState(List.of(BubbleColumnLayer.above(-1, false)));
        BubbleColumnState submerged =
                new BubbleColumnState(List.of(BubbleColumnLayer.inside(-3, true), BubbleColumnLayer.inside(-2, true)));

        assertEquals(0.002432F, BedrockBubbleColumnMovement.boatVelocityY(surface, 0.002432F), 0.0F);
        assertEquals(0.02256F - 0.03F - 0.03F, BedrockBubbleColumnMovement.boatVelocityY(submerged, 0.02256F), 0.0F);
    }
}
