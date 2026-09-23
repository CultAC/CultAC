package ac.cult.cultac.bedrock.prediction.simulation.collision;

import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockActorBox;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.world.BlockCollision;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
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
        return sweep(feet, delta, obstacles, playerDimensions, BedrockCoordinateFrame.IDENTITY);
    }

    public static MoveResult sweep(Vec3d feet, Vec3d delta, List<BlockCollision> obstacles,
                                   PlayerDimensionsState dimensions, BedrockCoordinateFrame frame) {
        return sweep(feet, delta, obstacles, dimensions, frame, BedrockCollisionClipper.DEFAULT_MAX_DEPENETRATION);
    }

    public static MoveResult sweep(Vec3d feet, Vec3d delta, List<BlockCollision> obstacles,
                                   PlayerDimensionsState dimensions, BedrockCoordinateFrame frame,
                                   float maximumDepenetration) {
        return sweep(playerBox(feet, dimensions, frame), delta, obstacles, frame, maximumDepenetration);
    }

    public static MoveResult sweep(WorldCollisionBox box, Vec3d delta, List<BlockCollision> obstacles,
                                   BedrockCoordinateFrame frame, float maximumDepenetration) {
        return sweepAxisOrder(box, delta, obstacles, frame, maximumDepenetration);
    }

    public static List<BlockCollision> collisionObstacles(BlockCollisionWorld world) {
        return world.collisions();
    }

    public static WorldCollisionBox playerBox(Vec3d feet, PlayerDimensionsState playerDimensions) {
        return playerBox(feet, playerDimensions, BedrockCoordinateFrame.IDENTITY);
    }

    public static WorldCollisionBox playerBox(Vec3d feet, PlayerDimensionsState playerDimensions,
                                              BedrockCoordinateFrame frame) {
        return BedrockActorBox.create(feet, playerDimensions, frame);
    }

    public static double f(double value) {
        return (double) (float) value;
    }

    private static MoveResult sweepAxisOrder(
        WorldCollisionBox box,
        Vec3d delta,
        List<BlockCollision> obstacles,
        BedrockCoordinateFrame frame,
        float maximumDepenetration
    ) {
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
            BedrockCollisionClipper.ClipResult clipped = BedrockCollisionClipper.clip(box, segments[phase], obstacles, frame, maximumDepenetration);
            Vec3d move = clipped.move();
            movedX += (float) move.x();
            movedY += (float) move.y();
            movedZ += (float) move.z();
            box = moveBedrock(box, move.x(), move.y(), move.z(), frame);
            if (phase == 0) {
                verticalPhaseBox = box;
            }
            if (clipped.yCollisionBlock().isPresent()) {
                yCollisionBlock = clipped.yCollisionBlock();
            }
        }

        Vec3d appliedDelta = new Vec3d(movedX, movedY, movedZ);
        Vec3d position = BedrockActorBox.feet(box, frame);

        return new MoveResult(
            position,
            appliedDelta,
            box,
            verticalPhaseBox,
            appliedDelta.x() != requested.x(),
            appliedDelta.y() != requested.y(),
            appliedDelta.z() != requested.z(),
            yCollisionBlock, frame
        );
    }

    static WorldCollisionBox moveBedrock(WorldCollisionBox box, double dx, double dy, double dz, BedrockCoordinateFrame frame) {
        return BedrockActorBox.move(box, new Vec3d(dx, dy, dz), frame);
    }

    public record MoveResult(
        Vec3d position,
        Vec3d appliedDelta,
        WorldCollisionBox finalBox,
        WorldCollisionBox verticalPhaseBox,
        boolean xCollision,
        boolean yCollision,
        boolean zCollision,
        Optional<PlacedBlockCollision> yCollisionBlock,
        BedrockCoordinateFrame coordinateFrame
    ) {
        public MoveResult(Vec3d position, Vec3d appliedDelta, WorldCollisionBox finalBox,
                          WorldCollisionBox verticalPhaseBox, boolean xCollision, boolean yCollision,
                          boolean zCollision, Optional<PlacedBlockCollision> yCollisionBlock) {
            this(position, appliedDelta, finalBox, verticalPhaseBox, xCollision, yCollision, zCollision,
                    yCollisionBlock, BedrockCoordinateFrame.IDENTITY);
        }

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
