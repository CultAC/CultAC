package ac.cult.cultac.bedrock.prediction.simulation.collision;

import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.world.BlockCollision;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import java.util.Optional;

public final class BedrockStandingBlockResolver {
    public static final double QUERY_Y_OFFSET = 0.2D;
    private static final double CENTER_SCALE = 0.5D;

    private BedrockStandingBlockResolver() {
    }

    public static Optional<StandingSupport> resolve(
        Vec3d feet,
        BlockCollisionWorld world,
        PlayerDimensionsState dimensions
    ) {
        return resolve(BedrockCollisionSweep.playerBox(feet, dimensions, world.coordinateFrame()), world.collisions(), world.coordinateFrame());
    }

    public static Optional<StandingSupport> resolve(
        WorldCollisionBox collisionBox,
        Iterable<BlockCollision> collisionShapes
    ) {
        return resolve(collisionBox, collisionShapes, BedrockCoordinateFrame.IDENTITY);
    }

    public static Optional<StandingSupport> resolve(WorldCollisionBox collisionBox,
            Iterable<BlockCollision> collisionShapes, BedrockCoordinateFrame frame) {
        WorldCollisionBox query = collisionBox.move(0.0D, -QUERY_Y_OFFSET, 0.0D);
        double centerX = frame.roundX((query.minX() + query.maxX()) * CENTER_SCALE);
        double centerY = f((query.minY() + query.maxY()) * CENTER_SCALE);
        double centerZ = frame.roundZ((query.minZ() + query.maxZ()) * CENTER_SCALE);
        StandingSupport selected = null;
        float selectedVerticalGap = Float.POSITIVE_INFINITY;
        float selectedCenterDistance = Float.POSITIVE_INFINITY;
        for (BlockCollision obstacle : collisionShapes) {
            WorldCollisionBox box = obstacle.box();
            if (obstacle.block().isEmpty() || !verticalQueryContainsShape(query, box)) {
                continue;
            }
            double boxCenterX = frame.roundX((box.minX() + box.maxX()) * CENTER_SCALE);
            double boxCenterY = f((box.minY() + box.maxY()) * CENTER_SCALE);
            double boxCenterZ = frame.roundZ((box.minZ() + box.maxZ()) * CENTER_SCALE);
            float verticalGap = (float) (query.minY() - boxCenterY);
            if (verticalGap < 0.0F) {
                continue;
            }
            float centerDistance = (float) squaredDistance(
                centerX, centerY, centerZ, boxCenterX, boxCenterY, boxCenterZ
            );
            if (verticalGap < selectedVerticalGap
                || verticalGap == selectedVerticalGap && centerDistance < selectedCenterDistance) {
                selected = new StandingSupport(obstacle.block().get(), box.maxY());
                selectedVerticalGap = verticalGap;
                selectedCenterDistance = centerDistance;
            }
        }
        return Optional.ofNullable(selected);
    }

    private static boolean verticalQueryContainsShape(WorldCollisionBox query, WorldCollisionBox shape) {
        double epsilon = BedrockCollisionSweep.CONTACT_EPSILON;
        return query.maxY() > shape.minY()
            && query.minY() < shape.maxY()
            && query.maxX() - shape.minX() > epsilon
            && shape.maxX() - query.minX() > epsilon
            && query.maxZ() - shape.minZ() > epsilon
            && shape.maxZ() - query.minZ() > epsilon;
    }

    private static double f(double value) {
        return (float) value;
    }

    private static double squaredDistance(
        double ax,
        double ay,
        double az,
        double bx,
        double by,
        double bz
    ) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }

    public record StandingSupport(PlacedBlockCollision block, double surfaceY) {
    }
}
