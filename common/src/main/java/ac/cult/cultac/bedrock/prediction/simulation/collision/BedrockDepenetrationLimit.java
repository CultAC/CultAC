package ac.cult.cultac.bedrock.prediction.simulation.collision;

import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;

/**
 * How deep a shape may overlap the moving box on all three axes and still be left through its nearest face: the
 * depth on the axis of that face is compared with this limit's component for the axis. A deeper overlap leaves the
 * shape out of the move.
 */
public record BedrockDepenetrationLimit(float x, float y, float z) {
    private static final BedrockDepenetrationLimit SOLID_ACTOR = uniform(0.1F);
    // A happy ghast lifts a mover whose feet are up to two blocks inside its top onto it.
    private static final BedrockDepenetrationLimit HAPPY_GHAST = new BedrockDepenetrationLimit(0.1F, 2.0F, 0.1F);

    public static BedrockDepenetrationLimit uniform(float depth) {
        return new BedrockDepenetrationLimit(depth, depth, depth);
    }

    /** The limit a solid actor gives every move that collected it; null for a block. */
    static BedrockDepenetrationLimit ofSolidActor(PlacedBlockCollision shape) {
        return switch (shape.bedrockIdentifier()) {
            case PlacedBlockCollision.SOLID_ACTOR -> SOLID_ACTOR;
            case PlacedBlockCollision.HAPPY_GHAST_ACTOR -> HAPPY_GHAST;
            default -> null;
        };
    }

    BedrockDepenetrationLimit atLeast(BedrockDepenetrationLimit other) {
        return x >= other.x && y >= other.y && z >= other.z
                ? this
                : new BedrockDepenetrationLimit(Math.max(x, other.x), Math.max(y, other.y), Math.max(z, other.z));
    }
}
