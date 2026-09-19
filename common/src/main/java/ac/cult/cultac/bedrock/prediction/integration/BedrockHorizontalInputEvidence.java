package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.protocol.BedrockMoveVector;

record BedrockHorizontalInputEvidence(
    Vec3d requiredInput,
    Vec3d observedInput,
    double limit,
    double excess
) {
    static BedrockHorizontalInputEvidence from(
        BedrockMovementResult result,
        Vec3d actualPosition,
        Vec3d inputReferencePosition,
        BedrockMoveVector observedMoveVector
    ) {
        Vec3d requiredInput = requiredHorizontalInput(result, actualPosition, inputReferencePosition);
        double limit = result.horizontalInputLimit();
        double radius = result.horizontalInputRadius();
        Vec3d observedInput = observedHorizontalInput(observedMoveVector, radius);
        double excess;
        if (result.previousState().isHorse()) {
            // Undo the vehicle's direction scales before checking the legal input radius.
            Vec3d riderInput = new Vec3d(requiredInput.x() * 2.0D, 0.0D,
                    requiredInput.z() <= 0.0D ? requiredInput.z() * 4.0D : requiredInput.z());
            excess = Math.max(0.0D, horizontalMagnitude(riderInput) - radius);
            if (result.groundJumpApplied()) {
                boolean forward = result.predictedState().horse().forwardJump();
                excess = Math.max(excess, forward ? -requiredInput.z() : requiredInput.z());
            }
        } else {
            excess = Math.max(0.0D, horizontalMagnitude(requiredInput) - radius);
        }
        return new BedrockHorizontalInputEvidence(
            requiredInput,
            observedInput,
            limit,
            excess
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

    private static Vec3d observedHorizontalInput(BedrockMoveVector observedMoveVector, double radius) {
        BedrockMoveVector vector = observedMoveVector == null ? BedrockMoveVector.ZERO : observedMoveVector;
        return new Vec3d(
            (double) vector.x() * radius,
            0.0D,
            (double) vector.z() * radius
        );
    }

    private static double horizontalMagnitude(Vec3d input) {
        return Math.sqrt(input.x() * input.x() + input.z() * input.z());
    }
}
