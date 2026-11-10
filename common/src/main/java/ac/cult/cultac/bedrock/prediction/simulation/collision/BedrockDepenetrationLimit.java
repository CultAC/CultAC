package ac.cult.cultac.bedrock.prediction.simulation.collision;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import java.util.List;
import java.util.Map;

/**
 * How deep a shape may overlap the moving box on all three axes and still be left through its nearest face: the
 * depth on the axis of that face is compared with this limit's component for the axis. A deeper overlap leaves the
 * shape out of the move.
 */
public record BedrockDepenetrationLimit(float x, float y, float z) {
    /** The limit a solid actor gives every move that collects it, unless its type gives its own. */
    public static final BedrockDepenetrationLimit SOLID_ACTOR = uniform(0.1F);
    /** A happy ghast lifts a mover whose feet are up to two blocks inside its top onto it. */
    public static final BedrockDepenetrationLimit HAPPY_GHAST = new BedrockDepenetrationLimit(0.1F, 2.0F, 0.1F);

    private static final String SOLID_ACTOR_LIMIT = "solidActorDepenetrationLimit";

    public static BedrockDepenetrationLimit uniform(float depth) {
        return new BedrockDepenetrationLimit(depth, depth, depth);
    }

    /** The shape of a solid actor's box, which gives every move that collects it this limit. */
    public PlacedBlockCollision solidActor(WorldCollisionBox box) {
        List<WorldCollisionBox> boxes = List.of(box);
        return PlacedBlockCollision.manual(
                new BlockPosition(
                        (int) Math.floor(box.minX()), (int) Math.floor(box.minY()), (int) Math.floor(box.minZ())),
                PlacedBlockCollision.SOLID_ACTOR,
                PlacedBlockCollision.SOLID_ACTOR,
                Map.of(SOLID_ACTOR_LIMIT, List.of(x, y, z)),
                boxes,
                boxes,
                boxes);
    }

    /** The limit a solid actor gives every move that collected it; null for a block. */
    static BedrockDepenetrationLimit ofSolidActor(PlacedBlockCollision shape) {
        if (!shape.isEntityCollision() || !(shape.bedrockState().get(SOLID_ACTOR_LIMIT) instanceof List<?> limit)) {
            return null;
        }
        return new BedrockDepenetrationLimit(component(limit, 0), component(limit, 1), component(limit, 2));
    }

    private static float component(List<?> limit, int axis) {
        return ((Number) limit.get(axis)).floatValue();
    }

    BedrockDepenetrationLimit atLeast(BedrockDepenetrationLimit other) {
        return x >= other.x && y >= other.y && z >= other.z
                ? this
                : new BedrockDepenetrationLimit(Math.max(x, other.x), Math.max(y, other.y), Math.max(z, other.z));
    }
}
