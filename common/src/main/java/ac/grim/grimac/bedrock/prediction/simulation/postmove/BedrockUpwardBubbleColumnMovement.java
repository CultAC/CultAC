package ac.grim.grimac.bedrock.prediction.simulation.postmove;

import ac.grim.grimac.bedrock.prediction.world.BedrockMovementContext;
import ac.grim.grimac.bedrock.prediction.world.BubbleColumnLayer;
import ac.grim.grimac.bedrock.prediction.world.BubbleColumnLayerType;
import ac.grim.grimac.bedrock.prediction.world.BubbleColumnState;

final class BedrockUpwardBubbleColumnMovement {
    private static final double INSIDE_ACCELERATION = 0.06D;
    private static final double ABOVE_ACCELERATION = 0.1D;
    private static final double INSIDE_VELOCITY_CAP = 0.7D;
    private static final double ABOVE_VELOCITY_CAP = 1.8D;

    private BedrockUpwardBubbleColumnMovement() {
    }

    static double velocityY(
        BedrockMovementContext context,
        double currentVelocityY,
        double feetY,
        long simulationTick
    ) {
        return velocityAfterBubble(
            context.worldState().fluidState().bubbleColumnState(),
            currentVelocityY,
            feetY,
            simulationTick
        );
    }

    private static double velocityAfterBubble(
        BubbleColumnState bubbleColumn,
        double currentVelocityY,
        double feetY,
        long simulationTick
    ) {
        if (!Double.isFinite(currentVelocityY)) {
            throw new IllegalArgumentException("bubble column vertical velocity must be finite");
        }
        if (!bubbleColumn.layers().isEmpty()) {
            return layerVelocityAfterBubble(bubbleColumn, currentVelocityY, feetY, simulationTick);
        }
        if (!bubbleColumn.active() || feetY >= bubbleColumn.topExitFeetY()) {
            return currentVelocityY;
        }
        int minBlockY = Math.max((int) Math.floor(feetY), (int) Math.floor(bubbleColumn.minY()));
        int maxBlockY = Math.min(
            (int) Math.floor(feetY + bubbleColumn.playerHeight()),
            (int) Math.floor(bubbleColumn.maxY())
        );
        double velocityY = currentVelocityY;
        int topBlockY = (int) Math.floor(bubbleColumn.topAboveY());
        for (int blockY = minBlockY; blockY <= maxBlockY; blockY++) {
            velocityY = applyAcceleration(
                velocityY,
                blockY == topBlockY ? BubbleColumnLayerType.ABOVE : BubbleColumnLayerType.INSIDE
            );
        }
        return velocityY;
    }

    private static double layerVelocityAfterBubble(
        BubbleColumnState bubbleColumn,
        double currentVelocityY,
        double feetY,
        long simulationTick
    ) {
        int minBlockY = (int) Math.floor(feetY + 0.001D);
        int maxBlockY = (int) Math.floor(feetY + bubbleColumn.playerHeight() - 0.001D);
        double velocityY = currentVelocityY;
        for (BubbleColumnLayer layer : bubbleColumn.layers()) {
            if (layer.activeAt(simulationTick)
                && minBlockY <= layer.blockY()
                && layer.blockY() <= maxBlockY) {
                velocityY = applyAcceleration(velocityY, layer.type());
            }
        }
        return velocityY;
    }

    private static double applyAcceleration(double velocityY, BubbleColumnLayerType layerType) {
        if (layerType == BubbleColumnLayerType.ABOVE) {
            return Math.min(velocityY + ABOVE_ACCELERATION, ABOVE_VELOCITY_CAP);
        }
        return Math.min(
            velocityY + INSIDE_ACCELERATION,
            INSIDE_VELOCITY_CAP
        );
    }
}
