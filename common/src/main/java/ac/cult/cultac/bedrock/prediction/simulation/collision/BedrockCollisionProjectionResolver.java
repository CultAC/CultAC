package ac.cult.cultac.bedrock.prediction.simulation.collision;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import java.util.Optional;

public final class BedrockCollisionProjectionResolver {
    private BedrockCollisionProjectionResolver() {
    }

    public static Optional<Vec3d> supportPosition(BedrockMovementResult movementResult, Vec3d requestedDelta) {
        if (movementResult == null || requestedDelta == null) {
            return Optional.empty();
        }
        BlockCollisionWorld blockWorld = movementResult.movementContext().worldState().blockCollisionWorld();
        if (blockWorld.isEmpty()) {
            return Optional.empty();
        }
        Vec3d feet = movementResult.previousState().physicalFeetPosition();
        BedrockCollisionSweep.MoveResult baseMove = BedrockCollisionSweep.sweep(
                feet,
                requestedDelta,
                BedrockCollisionSweep.collisionObstacles(blockWorld),
                movementResult.movementContext().playerDimensionsState());
        return downwardSupportCollision(feet, requestedDelta, baseMove)
                ? Optional.of(baseMove.position())
                : Optional.empty();
    }

    public static Optional<Projection> resolve(BedrockMovementResult movementResult, Vec3d requestedDelta) {
        if (movementResult == null || requestedDelta == null) {
            return Optional.empty();
        }
        BlockCollisionWorld blockWorld = movementResult.movementContext().worldState().blockCollisionWorld();
        Vec3d feet = movementResult.previousState().physicalFeetPosition();
        if (blockWorld.isEmpty()) {
            return Optional.empty();
        }
        BedrockEntityMove.CollisionMove collisionMove = BedrockEntityMove.collide(
                movementResult.previousState(),
                requestedDelta,
                blockWorld,
                movementResult.movementContext().playerDimensionsState(),
                movementResult.canStep(),
                movementResult.maxUpStep());
        BedrockEntityMove.FinalizedMove finalizedMove = BedrockEntityMove.finalizeMoveOutput(
                movementResult.previousState(),
                movementResult.movementContext().playerDimensionsState(),
                requestedDelta,
                requestedDelta,
                collisionMove);
        return Optional.of(new Projection(requestedDelta, finalizedMove, collisionMove));
    }

    public static Optional<Vec3d> resolvedPosition(BedrockMovementResult movementResult, Vec3d requestedDelta) {
        if (movementResult != null
                && movementResult.movementContext().worldState().blockCollisionWorld().isEmpty()) {
            return Optional.of(movementResult.previousState().physicalFeetPosition().add(requestedDelta));
        }
        return resolve(movementResult, requestedDelta).map(projection -> projection.move().position());
    }

    private static boolean downwardSupportCollision(
            Vec3d feet,
            Vec3d requestedDelta,
            BedrockCollisionSweep.MoveResult move
    ) {

        return requestedDelta.y() < -BedrockCollisionSweep.EPSILON
                && move.yCollision()
                && move.appliedDelta().y() > requestedDelta.y() + BedrockCollisionSweep.EPSILON
                && Math.abs(move.position().y() - feet.y()) <= BedrockCollisionSweep.EPSILON;
    }

    public record Projection(
        Vec3d requestedDelta,
        BedrockEntityMove.FinalizedMove move,
        BedrockEntityMove.CollisionMove collisionMove
    ) {
    }
}
