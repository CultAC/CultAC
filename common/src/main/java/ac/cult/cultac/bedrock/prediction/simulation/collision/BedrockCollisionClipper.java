package ac.cult.cultac.bedrock.prediction.simulation.collision;

import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.world.BlockCollision;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class BedrockCollisionClipper {
    // The ordinary move-request configuration supplies the unit vector as
    // its per-axis depenetration limit.
    private static final float MAX_DEPENETRATION = 1.0F;

    private BedrockCollisionClipper() {
    }

    static ClipResult clip(
        WorldCollisionBox moving,
        Vec3d requestedMove,
        List<BlockCollision> obstacles
    ) {
        return clip(moving, requestedMove, obstacles, BedrockCoordinateFrame.IDENTITY);
    }

    static ClipResult clip(WorldCollisionBox moving, Vec3d requestedMove, List<BlockCollision> obstacles,
                           BedrockCoordinateFrame frame) {
        FloatMove move = FloatMove.from(requestedMove);
        Optional<PlacedBlockCollision> yCollisionBlock = Optional.empty();
        // The vanilla swept-move consumes shapes in reverse insertion
        // order and moves the actor only after the phase.
        for (int index = obstacles.size() - 1; index >= 0; index--) {
            BlockCollision obstacle = obstacles.get(index);
            AabbClip clip = clipCollide(obstacle.box(), moving, move, frame);
            FloatMove clippedMove = clip.penetration() <= MAX_DEPENETRATION
                ? clip.depenetratedMove()
                : clip.normalMove();
            if (clippedMove.y() != move.y()) {
                yCollisionBlock = obstacle.block();
            }
            move = clippedMove;
        }
        return new ClipResult(move.toVec3d(), yCollisionBlock);
    }

    private static AabbClip clipCollide(
        WorldCollisionBox obstacle,
        WorldCollisionBox moving,
        FloatMove requestedMove,
        BedrockCoordinateFrame frame
    ) {
        if (frame.localX(obstacle.maxX()) <= frame.localX(obstacle.minX())
            || f(obstacle.maxY()) <= f(obstacle.minY())
            || frame.localZ(obstacle.maxZ()) <= frame.localZ(obstacle.minZ())) {
            return AabbClip.unchanged(requestedMove);
        }

        AxisOverlap x = axisOverlap(frame.localX(obstacle.minX()), frame.localX(obstacle.maxX()), frame.localX(moving.minX()), frame.localX(moving.maxX()));
        AxisOverlap y = axisOverlap(f(obstacle.minY()), f(obstacle.maxY()), f(moving.minY()), f(moving.maxY()));
        AxisOverlap z = axisOverlap(frame.localZ(obstacle.minZ()), frame.localZ(obstacle.maxZ()), frame.localZ(moving.minZ()), frame.localZ(moving.maxZ()));
        int separatedAxes = (x.overlapping() ? 0 : 1) + (y.overlapping() ? 0 : 1) + (z.overlapping() ? 0 : 1);
        if (separatedAxes >= 2) {
            return AabbClip.unchanged(requestedMove);
        }

        if (separatedAxes == 1) {
            SweepAxis axis = !x.overlapping() ? SweepAxis.X : !y.overlapping() ? SweepAxis.Y : SweepAxis.Z;
            AxisOverlap separation = overlap(axis, x, y, z);
            float requested = requestedMove.component(axis);
            if (0.0F < separation.depth() - requested * separation.sign()) {
                FloatMove clipped = requestedMove.with(axis, separation.depth() * separation.sign());
                return new AabbClip(0.0F, axis, clipped, clipped);
            }
            return AabbClip.unchanged(requestedMove);
        }

        // Full overlaps resolve through the nearest face, with X/Y/Z tie priority.
        SweepAxis axis = SweepAxis.X;
        AxisOverlap nearest = x;
        if (y.depth() < nearest.depth()) {
            axis = SweepAxis.Y;
            nearest = y;
        }
        if (z.depth() < nearest.depth()) {
            axis = SweepAxis.Z;
            nearest = z;
        }

        float signedExit = nearest.depth() * nearest.sign();
        float requested = requestedMove.component(axis);
        float depenetrated = signedExit > 0.0F
            ? Math.max(requested, signedExit)
            : Math.min(requested, signedExit);
        return new AabbClip(
            nearest.depth(),
            axis,
            requestedMove,
            requestedMove.with(axis, depenetrated)
        );
    }

    private static AxisOverlap axisOverlap(
        float obstacleMin,
        float obstacleMax,
        float movingMin,
        float movingMax
    ) {
        float positiveExit = clean(obstacleMax - movingMin);
        float negativeExit = clean(movingMax - obstacleMin);
        if (negativeExit <= 0.0F) {
            return new AxisOverlap(negativeExit, -1.0F, false);
        }
        if (positiveExit <= 0.0F) {
            return new AxisOverlap(positiveExit, 1.0F, false);
        }
        return positiveExit <= negativeExit
            ? new AxisOverlap(positiveExit, 1.0F, true)
            : new AxisOverlap(negativeExit, -1.0F, true);
    }

    private static AxisOverlap overlap(
        SweepAxis axis,
        AxisOverlap x,
        AxisOverlap y,
        AxisOverlap z
    ) {
        return switch (axis) {
            case X -> x;
            case Y -> y;
            case Z -> z;
        };
    }

    private static float clean(float value) {

        return Math.abs(value) > (float) BedrockCollisionSweep.CONTACT_EPSILON ? value : 0.0F;
    }

    private static float f(double value) {
        return (float) value;
    }

    record ClipResult(Vec3d move, Optional<PlacedBlockCollision> yCollisionBlock) {
        ClipResult {
            move = Objects.requireNonNull(move, "move");
            yCollisionBlock = Objects.requireNonNull(yCollisionBlock, "yCollisionBlock");
        }
    }

    private record AabbClip(
        float penetration,
        SweepAxis axis,
        FloatMove normalMove,
        FloatMove depenetratedMove
    ) {
        private AabbClip {
            axis = Objects.requireNonNull(axis, "axis");
            normalMove = Objects.requireNonNull(normalMove, "normalMove");
            depenetratedMove = Objects.requireNonNull(depenetratedMove, "depenetratedMove");
        }

        private static AabbClip unchanged(FloatMove move) {
            return new AabbClip(0.0F, SweepAxis.X, move, move);
        }
    }

    private record AxisOverlap(float depth, float sign, boolean overlapping) {
    }

    private record FloatMove(float x, float y, float z) {
        private static FloatMove from(Vec3d move) {
            return new FloatMove(f(move.x()), f(move.y()), f(move.z()));
        }

        private float component(SweepAxis axis) {
            return switch (axis) {
                case X -> x;
                case Y -> y;
                case Z -> z;
            };
        }

        private FloatMove with(SweepAxis axis, float value) {
            return switch (axis) {
                case X -> new FloatMove(value, y, z);
                case Y -> new FloatMove(x, value, z);
                case Z -> new FloatMove(x, y, value);
            };
        }

        private Vec3d toVec3d() {
            return new Vec3d(x, y, z);
        }
    }

    private enum SweepAxis {
        X,
        Y,
        Z
    }
}
