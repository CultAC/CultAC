package ac.grim.grimac.bedrock.prediction.simulation.collision;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.model.PlayerDimensionsState;
import ac.grim.grimac.bedrock.prediction.state.BedrockMovementState;
import ac.grim.grimac.bedrock.prediction.world.BlockCollisionWorld;

public final class BedrockCollisionProbe {
    private BedrockCollisionProbe() {
    }

    public static Result probe(
            BedrockMovementState previousState,
            BlockCollisionWorld blockWorld,
            PlayerDimensionsState dimensions,
            Vec3d attemptedDelta,
            boolean canStep,
            double maxUpStep
    ) {
        Query query = query(previousState, blockWorld, dimensions, attemptedDelta, canStep, maxUpStep);
        if (query == null) {
            return Result.NONE;
        }
        if (canStep && query.move().steppedUp()) {
            return fromStepClearedMove(previousState, attemptedDelta, query.move().baseMove());
        }
        return fromMove(previousState, attemptedDelta, query.selectedMove());
    }

    public static Vec3d project(
            BedrockMovementState previousState,
            BlockCollisionWorld blockWorld,
            PlayerDimensionsState dimensions,
            Vec3d attemptedDelta,
            boolean canStep,
            double maxUpStep
    ) {
        Query query = query(previousState, blockWorld, dimensions, attemptedDelta, canStep, maxUpStep);
        if (query == null) {
            return attemptedDelta == null ? Vec3d.ZERO : attemptedDelta;
        }
        return query.selectedMove().position().subtract(previousState.physicalFeetPosition());
    }

    private static Query query(
        BedrockMovementState previousState,
        BlockCollisionWorld blockWorld,
        PlayerDimensionsState dimensions,
        Vec3d attemptedDelta,
        boolean canStep,
        double maxUpStep
    ) {
        if (previousState == null || blockWorld == null || blockWorld.isEmpty() || dimensions == null || attemptedDelta == null) {
            return null;
        }
        BedrockEntityMove.CollisionMove move = BedrockEntityMove.collide(
            previousState, attemptedDelta, blockWorld, dimensions, canStep, maxUpStep
        );
        return new Query(move, canStep ? move.selectedMove() : move.baseMove());
    }

    public static HorizontalContactAxes horizontalContactAxes(
            Vec3d feet,
            BlockCollisionWorld blockWorld,
            PlayerDimensionsState dimensions
    ) {
        if (feet == null || blockWorld == null || blockWorld.isEmpty() || dimensions == null) {
            return HorizontalContactAxes.NONE;
        }
        BedrockBlockCollisionResolver.HorizontalContactAxes axes =
                BedrockBlockCollisionResolver.horizontalContactAxes(
                        feet,
                        BedrockCollisionSweep.collisionObstacles(blockWorld),
                        dimensions);
        return new HorizontalContactAxes(axes.xContact(), axes.zContact());
    }

    private static Result fromStepClearedMove(
            BedrockMovementState previousState,
            Vec3d attemptedDelta,
            BedrockCollisionSweep.MoveResult baseMove
    ) {
        double movedY = baseMove.position().y() - previousState.physicalFeetPosition().y();
        return new Result(
                false,
                attemptedDelta.x(),
                false,
                attemptedDelta.z(),
                baseMove.yCollision() && attemptedDelta.y() > 0.0D,
                baseMove.yCollision() && attemptedDelta.y() < 0.0D,
                movedY);
    }

    private static Result fromMove(
            BedrockMovementState previousState,
            Vec3d attemptedDelta,
            BedrockCollisionSweep.MoveResult move
    ) {
        double movedY = move.position().y() - previousState.physicalFeetPosition().y();
        return new Result(
                move.xCollision(),
                move.appliedDelta().x(),
                move.zCollision(),
                move.appliedDelta().z(),
                move.yCollision() && attemptedDelta.y() > 0.0D,
                move.yCollision() && attemptedDelta.y() < 0.0D,
                movedY);
    }

    public record Result(
            boolean xCollision,
            double xResult,
            boolean zCollision,
            double zResult,
            boolean yPosCollision,
            boolean yNegCollision,
            double yResult
    ) {
        private static final Result NONE = new Result(false, 0.0D, false, 0.0D, false, false, 0.0D);
    }

    public record HorizontalContactAxes(boolean xContact, boolean zContact) {
        private static final HorizontalContactAxes NONE = new HorizontalContactAxes(false, false);
    }

    private record Query(
        BedrockEntityMove.CollisionMove move,
        BedrockCollisionSweep.MoveResult selectedMove
    ) {
    }
}
