package ac.cult.cultac.bedrock.prediction.simulation.postmove;

import ac.cult.cultac.bedrock.prediction.world.BubbleColumnLayer;
import ac.cult.cultac.bedrock.prediction.world.BubbleColumnLayerType;
import ac.cult.cultac.bedrock.prediction.world.BubbleColumnState;

/** Each bubble column block the box touches after the move pushes the actor once. */
final class BedrockBubbleColumnMovement {
    private BedrockBubbleColumnMovement() {}

    static double velocityY(BubbleColumnState bubbleColumn, double velocityY) {
        for (BubbleColumnLayer layer : bubbleColumn.layers()) {
            boolean above = layer.type() == BubbleColumnLayerType.ABOVE;
            velocityY = layer.dragDown()
                    ? Math.max(velocityY - 0.03D, above ? -0.9D : -0.3D)
                    : Math.min(velocityY + (above ? 0.1D : 0.06D), above ? 1.8D : 0.7D);
        }
        return velocityY;
    }

    /** A boat is pushed in float steps, and only by the blocks it is submerged in. */
    static float boatVelocityY(BubbleColumnState bubbleColumn, float velocityY) {
        for (BubbleColumnLayer layer : bubbleColumn.layers()) {
            if (layer.type() == BubbleColumnLayerType.INSIDE) {
                velocityY = layer.dragDown()
                        ? Math.max(velocityY - 0.03F, -0.3F)
                        : Math.min(velocityY + 0.06F, 0.7F);
            }
        }
        return velocityY;
    }
}
