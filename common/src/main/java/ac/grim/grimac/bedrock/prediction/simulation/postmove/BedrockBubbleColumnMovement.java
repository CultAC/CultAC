package ac.grim.grimac.bedrock.prediction.simulation.postmove;

import ac.grim.grimac.bedrock.prediction.world.BubbleColumnLayer;
import ac.grim.grimac.bedrock.prediction.world.BubbleColumnLayerType;
import ac.grim.grimac.bedrock.prediction.world.BubbleColumnState;

final class BedrockBubbleColumnMovement {
    private BedrockBubbleColumnMovement() {
    }

    static double velocityY(
        BubbleColumnState bubbleColumn,
        double currentVelocityY,
        double feetY,
        long simulationTick
    ) {
        if (!Double.isFinite(currentVelocityY)) {
            throw new IllegalArgumentException("bubble column vertical velocity must be finite");
        }
        int minBlockY = (int) Math.floor(feetY + 0.001D);
        int maxBlockY = (int) Math.floor(feetY + bubbleColumn.playerHeight() - 0.001D);
        double velocityY = currentVelocityY;
        for (BubbleColumnLayer layer : bubbleColumn.layers()) {
            if (!layer.activeAt(simulationTick)
                || layer.blockY() < minBlockY
                || layer.blockY() > maxBlockY) {
                continue;
            }
            velocityY = applyLayer(velocityY, layer);
        }
        return velocityY;
    }

    private static double applyLayer(double velocityY, BubbleColumnLayer layer) {
        boolean above = layer.type() == BubbleColumnLayerType.ABOVE;
        if (layer.dragDown()) {
            // The vanilla entity-inside handling applies -0.03 with caps
            // -0.9/-0.3.
            return Math.max(velocityY - 0.03D, above ? -0.9D : -0.3D);
        }
        return Math.min(velocityY + (above ? 0.1D : 0.06D), above ? 1.8D : 0.7D);
    }
}
