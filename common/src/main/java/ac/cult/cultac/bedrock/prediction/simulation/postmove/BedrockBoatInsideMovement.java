package ac.cult.cultac.bedrock.prediction.simulation.postmove;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BubbleColumnLayerType;

final class BedrockBoatInsideMovement {
    private BedrockBoatInsideMovement() { }

    static Vec3d apply(BedrockMovementContext context, Vec3d velocity, Vec3d feet, long tick) {
        var bubbles = context.worldState().fluidState().bubbleColumnState();
        int minY = (int) Math.floor(feet.y() + 0.001D);
        int maxY = (int) Math.floor(feet.y() + context.playerDimensionsState().height() - 0.001D);
        float y = (float) velocity.y();
        for (var layer : bubbles.layers()) {
            // Above-column contact rocks the boat visually; only submerged contact changes velocity.
            if (layer.type() == BubbleColumnLayerType.ABOVE || !layer.activeAt(tick)
                    || layer.blockY() < minY || layer.blockY() > maxY) continue;
            y = layer.dragDown() ? Math.max(y - 0.03F, -0.3F) : Math.min(y + 0.06F, 0.7F);
        }
        return new Vec3d(velocity.x(), y, velocity.z());
    }
}
