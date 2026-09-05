package ac.cult.cultac.bedrock.prediction.simulation.reconciliation;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockCollisionProjectionResolver;
import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockCollisionSweep;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockActorDimensions;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockFluidStateResolver;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockTravelTypeResolver;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;

record BedrockAcceptedEndpointEvidence(
    BedrockMovementResult movementResult,
    BedrockMovementState state,
    Vec3d acceptedDiff,
    PlayerDimensionsState committedDimensions,
    BedrockMovementContext fluidContext,
    boolean projectedStep,
    BedrockCollisionProjectionResolver.Projection collisionProjection
) {
    static BedrockAcceptedEndpointEvidence from(
        BedrockMovementResult result,
        BedrockMovementState state,
        Vec3d acceptedDiff
    ) {
        return from(result, state, acceptedDiff, false);
    }

    static BedrockAcceptedEndpointEvidence from(
        BedrockMovementResult result,
        BedrockMovementState state,
        Vec3d acceptedDiff,
        boolean canStep
    ) {
        PlayerDimensionsState dimensions = BedrockActorDimensions.committedMovementDimensions(
            result.previousState(),
            result.movementContext().playerDimensionsState(),
            state.inputFrame()
        );
        BedrockMovementContext fluidContext = BedrockFluidStateResolver.withCurrentTickFluidStateFromBlockWorld(
            result.movementContext(),
            state.physicalFeetPosition(),
            result.movementContext().playerDimensionsState()
        );
        BedrockCollisionProjectionResolver.Projection collisionProjection = acceptedCollisionProjection(
            result, acceptedDiff
        );
        return new BedrockAcceptedEndpointEvidence(
            result,
            state,
            acceptedDiff,
            dimensions,
            fluidContext,
            canStep || collisionProjection != null && collisionProjection.collisionMove().steppedUp(),
            collisionProjection
        );
    }

    private static BedrockCollisionProjectionResolver.Projection acceptedCollisionProjection(
        BedrockMovementResult result,
        Vec3d acceptedDiff
    ) {
        Vec3d previous = result.previousState().physicalFeetPosition();
        Vec3d rawMove = result.rawPredictedPhysicalFeetPosition().subtract(previous);
        Vec3d requested = new Vec3d(acceptedDiff.x(), rawMove.y(), acceptedDiff.z());
        return BedrockCollisionProjectionResolver.resolve(result, requested)
            .filter(projection -> movementMatches(
                projection.collisionMove().selectedMove().appliedDelta(), acceptedDiff
            ))
            .orElse(null);
    }

    private static boolean movementMatches(Vec3d applied, Vec3d acceptedDiff) {
        return Math.abs(applied.x() - acceptedDiff.x()) <= BedrockCollisionSweep.CONTACT_EPSILON
            && Math.abs(applied.y() - acceptedDiff.y()) <= BedrockCollisionSweep.CONTACT_EPSILON
            && Math.abs(applied.z() - acceptedDiff.z()) <= BedrockCollisionSweep.CONTACT_EPSILON;
    }

    boolean waterTravelActive() {
        return BedrockTravelTypeResolver.waterActive(
            fluidContext,
            state.physicalFeetPosition(),
            movementResult.movementContext().playerDimensionsState()
        );
    }

    Medium movementBranch(BedrockCollisionFlags flags, boolean waterTravelActive) {
        return BedrockTravelTypeResolver.postMoveBranch(fluidContext, flags, waterTravelActive);
    }
}
