package ac.cult.cultac.bedrock.prediction.simulation.collision;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.world.BedrockBlockMetadata;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import java.util.List;
import java.util.Optional;

/**
 * The block an actor on the ground stands on for its surface effects. Each block counts as one box: its cell where it
 * collides as several, unless it names another or none. Of the blocks the bottom of the actor's box, lowered by 0.2,
 * cuts through, it is the one that reaches highest, and of equals the one whose centre is nearest.
 */
public final class BedrockStandingSurfaceBlock {
    private static final float QUERY_Y_OFFSET = 0.2F;

    private BedrockStandingSurfaceBlock() {}

    public static Optional<PlacedBlockCollision> resolve(
            Vec3d feet, BlockCollisionWorld world, PlayerDimensionsState dimensions) {
        BedrockCoordinateFrame frame = world.coordinateFrame();
        WorldCollisionBox actor = BedrockCollisionSweep.playerBox(feet, dimensions, frame);
        float minX = frame.localX(actor.minX());
        float maxX = frame.localX(actor.maxX());
        float minZ = frame.localZ(actor.minZ());
        float maxZ = frame.localZ(actor.maxZ());
        float y = (float) actor.minY() - QUERY_Y_OFFSET;
        float centerX = center(minX, maxX);
        float centerZ = center(minZ, maxZ);
        PlacedBlockCollision selected = null;
        float selectedTop = Float.NEGATIVE_INFINITY;
        float selectedDistance = Float.POSITIVE_INFINITY;
        for (PlacedBlockCollision block : world.blocks()) {
            WorldCollisionBox shape = block.isEntityCollision() ? null : shape(block);
            if (shape == null) continue;
            float shapeMinX = frame.localX(shape.minX());
            float shapeMaxX = frame.localX(shape.maxX());
            float bottom = (float) shape.minY();
            float top = (float) shape.maxY();
            float shapeMinZ = frame.localZ(shape.minZ());
            float shapeMaxZ = frame.localZ(shape.maxZ());
            if (!(shapeMinX < shapeMaxX && bottom < top && shapeMinZ < shapeMaxZ)
                    || !(minX < shapeMaxX && shapeMinX < maxX)
                    || !(bottom < y && y < top)
                    || !(minZ < shapeMaxZ && shapeMinZ < maxZ)
                    || top < selectedTop) continue;
            float dx = centerX - center(shapeMinX, shapeMaxX);
            float dy = y - center(bottom, top);
            float dz = centerZ - center(shapeMinZ, shapeMaxZ);
            float distance = dz * dz + (dy * dy + dx * dx);
            if (top > selectedTop || distance < selectedDistance) {
                selected = block;
                selectedTop = top;
                selectedDistance = distance;
            }
        }
        return Optional.ofNullable(selected);
    }

    private static WorldCollisionBox shape(PlacedBlockCollision block) {
        List<WorldCollisionBox> boxes = block.collisionBoxes();
        if (boxes.isEmpty()) return null;
        if (block.bedrockState().get(BedrockBlockMetadata.STANDING_SHAPE) instanceof List<?> bounds) {
            return bounds.isEmpty()
                    ? null
                    : new WorldCollisionBox(
                            bound(bounds, 0),
                            bound(bounds, 1),
                            bound(bounds, 2),
                            bound(bounds, 3),
                            bound(bounds, 4),
                            bound(bounds, 5));
        }
        if (boxes.size() == 1) return boxes.getFirst();
        BlockPosition cell = block.position();
        return new WorldCollisionBox(cell.x(), cell.y(), cell.z(), cell.x() + 1.0D, cell.y() + 1.0D, cell.z() + 1.0D);
    }

    private static double bound(List<?> bounds, int index) {
        return ((Number) bounds.get(index)).doubleValue();
    }

    private static float center(float min, float max) {
        return (max - min) * 0.5F + min;
    }
}
