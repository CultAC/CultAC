package ac.grim.grimac.bedrock.prediction.api;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.model.BlockMovementSlowdownState;
import ac.grim.grimac.bedrock.prediction.state.BedrockMovementState;
import ac.grim.grimac.bedrock.prediction.world.BedrockMovementContext;
import ac.grim.grimac.bedrock.prediction.world.HoneySlideState;
import java.util.Objects;

public record BedrockMovementResult(
    BedrockMovementState previousState,
    BedrockMovementContext movementContext,
    BedrockMovementContext postMoveContext,
    BedrockMovementState predictedState,
    Vec3d rawPredictedPhysicalFeetPosition,
    Vec3d collisionInputVelocity,
    boolean orderedPostMoveOwnsHorizontalVelocity,
    boolean orderedPostMoveOwnsVerticalVelocity,
    boolean standingBounceBounced,
    boolean standingSurfaceHorizontalSlowdownApplied,
    BlockMovementSlowdownState currentBlockMovementSlowdownState,
    HoneySlideState honeySlideState,
    boolean selectedGlidingTravel,
    boolean selectedWaterTravel,
    double horizontalInputLimit,
    double horizontalFriction,
    boolean steppedUp,
    boolean stepRetryAllowed,
    boolean canStep,
    double maxUpStep
) {
    public BedrockMovementResult {
        previousState = Objects.requireNonNull(previousState, "previousState");
        movementContext = Objects.requireNonNull(movementContext, "movementContext");
        postMoveContext = Objects.requireNonNull(postMoveContext, "postMoveContext");
        predictedState = Objects.requireNonNull(predictedState, "predictedState");
        rawPredictedPhysicalFeetPosition = Objects.requireNonNull(rawPredictedPhysicalFeetPosition, "rawPredictedPhysicalFeetPosition");
        collisionInputVelocity = Objects.requireNonNull(collisionInputVelocity, "collisionInputVelocity");
        currentBlockMovementSlowdownState = Objects.requireNonNull(currentBlockMovementSlowdownState, "currentBlockMovementSlowdownState");
        honeySlideState = Objects.requireNonNull(honeySlideState, "honeySlideState");
        horizontalInputLimit = Math.max(0.0D, horizontalInputLimit);
        if (!Double.isFinite(horizontalFriction) || horizontalFriction < 0.0D) {
            throw new IllegalArgumentException("horizontalFriction must be finite and non-negative");
        }
        if (!Double.isFinite(maxUpStep) || maxUpStep < 0.0D) {
            maxUpStep = 0.0D;
        }
    }

    public Vec3d predictedPosition() {
        return predictedState.physicalFeetPosition();
    }

    public Vec3d predictedVelocity() {
        return predictedState.velocity();
    }

    public boolean blockMovementSlowdownClearsVelocity() {
        return currentBlockMovementSlowdownState.clearVelocityAfterMove();
    }
}
