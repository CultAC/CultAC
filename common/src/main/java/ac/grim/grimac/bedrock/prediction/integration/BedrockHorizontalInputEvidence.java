package ac.grim.grimac.bedrock.prediction.integration;

import ac.grim.grimac.bedrock.prediction.api.BedrockMovementResult;
import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.protocol.BedrockMoveVector;

record BedrockHorizontalInputEvidence(
    Vec3d requiredInput,
    Vec3d observedInput,
    double limit,
    double excess
) {
    private static final float INPUT_FRICTION = 0.98F;

    static BedrockHorizontalInputEvidence from(
        BedrockMovementResult result,
        Vec3d actualPosition,
        Vec3d inputReferencePosition,
        BedrockMoveVector observedMoveVector
    ) {
        Vec3d requiredInput = requiredHorizontalInput(result, actualPosition, inputReferencePosition);
        double limit = result.horizontalInputLimit();
        Vec3d observedInput = observedHorizontalInput(observedMoveVector, limit);
        return new BedrockHorizontalInputEvidence(
            requiredInput,
            observedInput,
            limit,
            Math.max(0.0D, horizontalMagnitude(requiredInput) - limit * INPUT_FRICTION)
        );
    }

    private static Vec3d requiredHorizontalInput(
        BedrockMovementResult result,
        Vec3d actualPosition,
        Vec3d predictedPosition
    ) {
        Vec3d previousPosition = result.previousState().physicalFeetPosition();
        Vec3d predictedDelta = predictedPosition.subtract(previousPosition);
        Vec3d actualDelta = actualPosition.subtract(previousPosition);
        Vec3d requiredAcceleration = new Vec3d(
            actualDelta.x() - predictedDelta.x(),
            0.0D,
            actualDelta.z() - predictedDelta.z());
        return BedrockRequiredInputMath.horizontalInputForDelta(
            requiredAcceleration,
            result.predictedState().inputFrame().yaw());
    }

    private static Vec3d observedHorizontalInput(BedrockMoveVector observedMoveVector, double horizontalInputLimit) {
        BedrockMoveVector vector = observedMoveVector == null ? BedrockMoveVector.ZERO : observedMoveVector;
        double scale = horizontalInputLimit * INPUT_FRICTION;
        return new Vec3d(
            (double) vector.x() * scale,
            0.0D,
            (double) vector.z() * scale
        );
    }

    private static double horizontalMagnitude(Vec3d input) {
        return Math.sqrt(input.x() * input.x() + input.z() * input.z());
    }
}
