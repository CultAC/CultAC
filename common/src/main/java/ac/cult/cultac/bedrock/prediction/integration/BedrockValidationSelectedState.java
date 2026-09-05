package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;

final class BedrockValidationSelectedState {
    private BedrockValidationSelectedState() {
    }

    static BedrockMovementState nextTickBase(
            BedrockMovementResult movementResult,
            BedrockValidationSelection selection
    ) {
        if (movementResult == null || selection == null) {
            return null;
        }
        Vec3d nextTickBasePosition = nextTickBasePosition(selection);
        BedrockMovementState selectedState = movementResult.predictedState()
                .withPhysicalFeetPosition(nextTickBasePosition, displacementSquared(selection, nextTickBasePosition));

        return BedrockPacketHorizontalCollisionState.applyToValidationSelectedState(
                selectedState,
                movementResult.movementContext(),
                selection.authFrame(),
                nextTickBasePosition.subtract(selection.previousPosition()));
    }

    static Vec3d nextTickBasePosition(BedrockValidationSelection selection) {
        return selection.packetPosition() == null
                ? selection.validationSelectedPosition()
                : BedrockVectorAdapter.toBedrock(selection.packetPosition());
    }

    static double displacementSquared(BedrockValidationSelection selection, Vec3d nextTickBasePosition) {
        Vec3d previousPosition = selection.previousPosition();
        double dx = nextTickBasePosition.x() - previousPosition.x();
        double dy = nextTickBasePosition.y() - previousPosition.y();
        double dz = nextTickBasePosition.z() - previousPosition.z();
        return dx * dx + dy * dy + dz * dz;
    }
}
