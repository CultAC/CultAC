package ac.grim.grimac.bedrock.prediction.simulation.collision;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.grim.grimac.bedrock.prediction.model.PlayerDimensionsState;
import ac.grim.grimac.bedrock.prediction.world.BlockCollision;
import ac.grim.grimac.bedrock.prediction.world.BlockCollisionWorld;
import ac.grim.grimac.bedrock.prediction.world.PlacedBlockCollision;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class BedrockCollisionSweep {
    public static final double EPSILON = 1.0E-9D;
    public static final double CONTACT_EPSILON = 1.0E-6D;
    private BedrockCollisionSweep() {
    }

    public static MoveResult sweep(
        Vec3d feet,
        Vec3d delta,
        List<BlockCollision> obstacles,
        PlayerDimensionsState playerDimensions
    ) {
        return sweepAxisOrder(feet, delta, obstacles, playerDimensions);
    }

    public static List<BlockCollision> collisionObstacles(BlockCollisionWorld world) {
        return world.collisions();
    }

    public static WorldCollisionBox playerBox(Vec3d feet, PlayerDimensionsState playerDimensions) {
        double radius = PlayerDimensionsState.DEFAULT.equals(playerDimensions)
            ? BedrockBlockCollisionResolver.PLAYER_RADIUS
            : playerDimensions.radius();
        return new WorldCollisionBox(
            f(feet.x() - radius),
            f(feet.y()),
            f(feet.z() - radius),
            f(feet.x() + radius),
            f(feet.y() + playerDimensions.height()),
            f(feet.z() + radius)
        );
    }

    public static double f(double value) {
        return (double) (float) value;
    }

    private static MoveResult sweepAxisOrder(
        Vec3d feet,
        Vec3d delta,
        List<BlockCollision> obstacles,
        PlayerDimensionsState playerDimensions
    ) {
        WorldCollisionBox box = playerBox(feet, playerDimensions);
        Vec3d requested = new Vec3d(f(delta.x()), f(delta.y()), f(delta.z()));
        float movedX = 0.0F;
        float movedY = 0.0F;
        float movedZ = 0.0F;
        Optional<PlacedBlockCollision> yCollisionBlock = Optional.empty();
        WorldCollisionBox verticalPhaseBox = box;

        // The sweep includes every axis, including zero-length moves.
        Vec3d[] segments = {
            new Vec3d(0.0D, requested.y(), 0.0D),
            new Vec3d(requested.x(), 0.0D, 0.0D),
            new Vec3d(0.0D, 0.0D, requested.z())
        };
        for (int phase = 0; phase < segments.length; phase++) {
            BedrockCollisionClipper.ClipResult clipped = BedrockCollisionClipper.clip(box, segments[phase], obstacles);
            Vec3d move = clipped.move();
            movedX += (float) move.x();
            movedY += (float) move.y();
            movedZ += (float) move.z();
            box = moveBedrock(box, move.x(), move.y(), move.z());
            if (phase == 0) {
                verticalPhaseBox = box;
            }
            if (clipped.yCollisionBlock().isPresent()) {
                yCollisionBlock = clipped.yCollisionBlock();
            }
        }

        Vec3d appliedDelta = new Vec3d(movedX, movedY, movedZ);
        Vec3d position = new Vec3d(f(feet.x() + appliedDelta.x()), f(feet.y() + appliedDelta.y()), f(feet.z() + appliedDelta.z()));

        return new MoveResult(
            position,
            appliedDelta,
            box,
            verticalPhaseBox,
            appliedDelta.x() != requested.x(),
            appliedDelta.y() != requested.y(),
            appliedDelta.z() != requested.z(),
            yCollisionBlock
        );
    }

    static WorldCollisionBox moveBedrock(WorldCollisionBox box, double dx, double dy, double dz) {
        return new WorldCollisionBox(
            f(box.minX() + dx),
            f(box.minY() + dy),
            f(box.minZ() + dz),
            f(box.maxX() + dx),
            f(box.maxY() + dy),
            f(box.maxZ() + dz)
        );
    }

    public record MoveResult(
        Vec3d position,
        Vec3d appliedDelta,
        WorldCollisionBox finalBox,
        WorldCollisionBox verticalPhaseBox,
        boolean xCollision,
        boolean yCollision,
        boolean zCollision,
        Optional<PlacedBlockCollision> yCollisionBlock
    ) {
        public MoveResult {
            position = Objects.requireNonNull(position, "position");
            appliedDelta = Objects.requireNonNull(appliedDelta, "appliedDelta");
            finalBox = Objects.requireNonNull(finalBox, "finalBox");
            verticalPhaseBox = Objects.requireNonNull(verticalPhaseBox, "verticalPhaseBox");
            yCollisionBlock = Objects.requireNonNull(yCollisionBlock, "yCollisionBlock");
        }

        boolean horizontalCollision() {
            return xCollision || zCollision;
        }
    }

}
