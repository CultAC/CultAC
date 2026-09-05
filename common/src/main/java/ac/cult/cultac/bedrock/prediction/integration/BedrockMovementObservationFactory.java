package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.BedrockMovementObservation;
import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.protocol.BedrockMoveVector;
import net.minecraft.world.phys.Vec3;

final class BedrockMovementObservationFactory {
    private BedrockMovementObservationFactory() {
    }

    static BedrockMovementObservation fromValidationSelection(
            BedrockMovementResult result,
            Vec3 actualPhysicalFeet,
            Vec3d validationSelectedPosition,
            Vec3d inputReferencePosition,
            BedrockMoveVector observedMoveVector
    ) {
        if (result == null || actualPhysicalFeet == null || validationSelectedPosition == null || inputReferencePosition == null) {
            return null;
        }
        Vec3d actualPosition = BedrockVectorAdapter.toBedrock(actualPhysicalFeet);
        BedrockHorizontalInputEvidence horizontalInput = BedrockHorizontalInputEvidence.from(
                result,
                actualPosition,
                inputReferencePosition,
                observedMoveVector);
        double verticalOffset = verticalOffset(validationSelectedPosition, actualPosition);
        double validationOffset = validationOffset(verticalOffset, horizontalInput.excess());
        return BedrockMovementObservation.create(
                result,
                actualPhysicalFeet,
                validationSelectedPosition,
                horizontalInput.requiredInput(),
                horizontalInput.observedInput(),
                horizontalInput.limit(),
                horizontalInput.excess(),
                verticalOffset,
                validationOffset);
    }

    private static double verticalOffset(Vec3d predictedPosition, Vec3d actualPosition) {
        return Math.abs(predictedPosition.y() - actualPosition.y());
    }

    private static double validationOffset(double verticalOffset, double horizontalInputExcess) {
        return Math.sqrt(verticalOffset * verticalOffset + horizontalInputExcess * horizontalInputExcess);
    }

}
