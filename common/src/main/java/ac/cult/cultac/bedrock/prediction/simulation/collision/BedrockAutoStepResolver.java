package ac.cult.cultac.bedrock.prediction.simulation.collision;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BlockCollision;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class BedrockAutoStepResolver {
    private static final double EPSILON = BedrockCollisionSweep.EPSILON;

    private BedrockAutoStepResolver() {
    }

    static Optional<BedrockCollisionSweep.MoveResult> resolve(
        BedrockMovementState current,
        Vec3d requestedDelta,
        BedrockCollisionSweep.MoveResult baseMove,
        List<BlockCollision> obstacles,
        PlayerDimensionsState dimensions,
        double maxUpStep
    ) {
        if (maxUpStep <= 0.0D) {
            return Optional.empty();
        }

        Vec3d startFeet = current.physicalFeetPosition();
        WorldCollisionBox startBox = BedrockCollisionSweep.playerBox(startFeet, dimensions);
        List<BlockCollision> stepShapes = shapesIgnoringCeiling(startBox, obstacles);
        BedrockCollisionSweep.MoveResult upMove = BedrockCollisionSweep.sweep(
            startFeet,
            new Vec3d(requestedDelta.x(), maxUpStep, requestedDelta.z()),
            stepShapes,
            dimensions
        );

        double acceptedRise = upMove.appliedDelta().y();
        BedrockCollisionClipper.ClipResult downClip = BedrockCollisionClipper.clip(
            upMove.finalBox(),
            new Vec3d(0.0D, -acceptedRise, 0.0D),
            stepShapes
        );
        Vec3d finalDelta = addFloat(upMove.appliedDelta(), downClip.move());
        WorldCollisionBox finalBox = BedrockCollisionSweep.moveBedrock(
            upMove.finalBox(),
            downClip.move().x(),
            downClip.move().y(),
            downClip.move().z()
        );
        if (collides(finalBox, obstacles)
            || horizontalDistanceSquared(finalDelta) <= horizontalDistanceSquared(baseMove.appliedDelta()) + EPSILON) {
            return Optional.empty();
        }

        Vec3d finalPosition = new Vec3d(
            BedrockCollisionSweep.f(startFeet.x() + finalDelta.x()),
            BedrockCollisionSweep.f(startFeet.y() + finalDelta.y()),
            BedrockCollisionSweep.f(startFeet.z() + finalDelta.z())
        );
        Optional<PlacedBlockCollision> yCollisionBlock = downClip.yCollisionBlock().or(upMove::yCollisionBlock);
        Vec3d requested = new Vec3d(
            BedrockCollisionSweep.f(requestedDelta.x()),
            BedrockCollisionSweep.f(requestedDelta.y()),
            BedrockCollisionSweep.f(requestedDelta.z())
        );
        return Optional.of(new BedrockCollisionSweep.MoveResult(
            finalPosition,
            finalDelta,
            finalBox,
            upMove.verticalPhaseBox(),
            finalDelta.x() != requested.x(),
            finalDelta.y() != requested.y(),
            finalDelta.z() != requested.z(),
            yCollisionBlock
        ));
    }

    private static List<BlockCollision> shapesIgnoringCeiling(
        WorldCollisionBox actorBox,
        List<BlockCollision> obstacles
    ) {

        ArrayList<BlockCollision> shapes = new ArrayList<>(obstacles.size());
        for (BlockCollision obstacle : obstacles) {
            if (obstacle.box().minY() < actorBox.maxY()) {
                shapes.add(obstacle);
            }
        }
        return List.copyOf(shapes);
    }

    private static Vec3d addFloat(Vec3d left, Vec3d right) {
        return new Vec3d(
            (float) ((float) left.x() + (float) right.x()),
            (float) ((float) left.y() + (float) right.y()),
            (float) ((float) left.z() + (float) right.z())
        );
    }

    private static boolean collides(WorldCollisionBox box, List<BlockCollision> obstacles) {
        for (BlockCollision obstacle : obstacles) {
            if (box.intersects(obstacle.box())) {
                return true;
            }
        }
        return false;
    }

    private static double horizontalDistanceSquared(Vec3d move) {
        return move.x() * move.x() + move.z() * move.z();
    }
}
