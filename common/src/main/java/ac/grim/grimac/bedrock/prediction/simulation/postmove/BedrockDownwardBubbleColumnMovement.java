package ac.grim.grimac.bedrock.prediction.simulation.postmove;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.world.BubbleColumnLayer;
import ac.grim.grimac.bedrock.prediction.world.BubbleColumnLayerType;
import ac.grim.grimac.bedrock.prediction.world.BubbleColumnState;

final class BedrockDownwardBubbleColumnMovement {
    private static final double ACCELERATION = -0.03D;
    private static final double INSIDE_VELOCITY_CAP = -0.3D;
    private static final double ABOVE_VELOCITY_CAP = -0.9D;

    private BedrockDownwardBubbleColumnMovement() {
    }

    static Vec3d velocity(
        BubbleColumnState bubbleColumn,
        Vec3d currentVelocity,
        double feetY,
        long simulationTick
    ) {
        double velocityY = velocityAfterBubble(
            bubbleColumn,
            currentVelocity.y(),
            feetY,
            simulationTick,
            INSIDE_VELOCITY_CAP,
            ABOVE_VELOCITY_CAP
        );
        return new Vec3d(currentVelocity.x(), velocityY, currentVelocity.z());
    }

    private static double velocityAfterBubble(
        BubbleColumnState bubbleColumn,
        double currentVelocityY,
        double feetY,
        long simulationTick,
        double insideVelocityCap,
        double aboveVelocityCap
    ) {
        if (!Double.isFinite(currentVelocityY)) {
            throw new IllegalArgumentException("bubble column vertical velocity must be finite");
        }
        int minBlockY = (int) Math.floor(feetY + 0.001D);
        int maxBlockY = (int) Math.floor(feetY + bubbleColumn.playerHeight() - 0.001D);
        double velocityY = currentVelocityY;
        for (BubbleColumnLayer layer : bubbleColumn.layers()) {
            if (layer.activeAt(simulationTick)
                && minBlockY <= layer.blockY()
                && layer.blockY() <= maxBlockY) {
                boolean above = layer.type() == BubbleColumnLayerType.ABOVE;
                velocityY = acceleratedVelocity(
                    velocityY,
                    above ? aboveVelocityCap : insideVelocityCap
                );
            }
        }
        return velocityY;
    }

    private static double acceleratedVelocity(double currentVelocityY, double velocityCap) {
        return Math.max(
            currentVelocityY + ACCELERATION,
            velocityCap
        );
    }
}
