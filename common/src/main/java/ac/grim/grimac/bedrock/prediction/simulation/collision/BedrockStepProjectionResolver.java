package ac.grim.grimac.bedrock.prediction.simulation.collision;

import ac.grim.grimac.bedrock.prediction.api.BedrockMovementResult;
import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.model.PlayerDimensionsState;
import ac.grim.grimac.bedrock.prediction.world.BlockCollisionWorld;
import java.util.Optional;

public final class BedrockStepProjectionResolver {
    private BedrockStepProjectionResolver() {
    }

    public static Optional<Vec3d> candidatePosition(BedrockMovementResult movementResult) {
        if (movementResult == null || movementResult.maxUpStep() <= 0.0D) {
            return Optional.empty();
        }
        Vec3d feet = movementResult.previousState().physicalFeetPosition();
        return candidatePosition(movementResult, movementResult.rawPredictedPhysicalFeetPosition().subtract(feet));
    }

    public static Optional<Vec3d> candidatePosition(BedrockMovementResult movementResult, Vec3d requestedDelta) {
        if (movementResult == null || requestedDelta == null || movementResult.maxUpStep() <= 0.0D) {
            return Optional.empty();
        }
        BlockCollisionWorld blockWorld = movementResult.movementContext().worldState().blockCollisionWorld();
        if (blockWorld.isEmpty()) {
            return Optional.empty();
        }
        if (Math.abs(requestedDelta.x()) <= BedrockCollisionSweep.EPSILON
                && Math.abs(requestedDelta.z()) <= BedrockCollisionSweep.EPSILON) {
            return Optional.empty();
        }
        PlayerDimensionsState dimensions = movementResult.movementContext().playerDimensionsState();
        BedrockEntityMove.CollisionMove collisionMove = BedrockEntityMove.collide(
                movementResult.previousState(),
                requestedDelta,
                blockWorld,
                dimensions,
                true,
                movementResult.maxUpStep());
        BedrockCollisionSweep.MoveResult baseMove = collisionMove.baseMove();
        boolean horizontalMovementChanged = horizontalMovementChanged(requestedDelta, baseMove);
        if (!horizontalMovementChanged) {
            return Optional.empty();
        }
        return collisionMove.steppedUp()
                ? Optional.of(collisionMove.selectedMove().position())
                : Optional.empty();
    }

    private static boolean horizontalMovementChanged(
            Vec3d requestedDelta,
            BedrockCollisionSweep.MoveResult move
    ) {
        return Math.abs(requestedDelta.x() - move.appliedDelta().x()) > BedrockCollisionSweep.EPSILON
                || Math.abs(requestedDelta.z() - move.appliedDelta().z()) > BedrockCollisionSweep.EPSILON;
    }

}
