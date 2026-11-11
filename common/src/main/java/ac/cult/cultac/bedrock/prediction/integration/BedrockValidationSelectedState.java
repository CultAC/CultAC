package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;

final class BedrockValidationSelectedState {
    private BedrockValidationSelectedState() {}

    static BedrockMovementState nextTickBase(
            BedrockMovementResult movementResult, BedrockValidationSelection selection) {
        if (movementResult == null || selection == null) {
            return null;
        }
        Vec3d nextTickBasePosition = nextTickBasePosition(selection);
        BedrockMovementState selectedState = movementResult
                .predictedState()
                .withExternalDisplacement(selection.externalDisplacement())
                .withObservedPosition(nextTickBasePosition, displacementSquared(selection, nextTickBasePosition));

        if (!movementResult.travelActive()) {
            return selectedState;
        }

        return BedrockPacketHorizontalCollisionState.applyToValidationSelectedState(
                selectedState,
                movementResult.movementContext(),
                selection.authFrame(),
                nextTickBasePosition.subtract(selection.previousPosition()));
    }

    static BedrockMovementState candidateBase(
            BedrockMovementResult movementResult, BedrockValidationSelection selection) {
        return relocated(movementResult, selection.externalDisplacement(), selection.validationSelectedPosition());
    }

    /** The candidate's move, displaced by {@code external}, placed at the selected {@code position}. */
    static BedrockMovementState relocated(BedrockMovementResult movementResult, Vec3d external, Vec3d position) {
        Vec3d moved = position.subtract(movementResult.previousState().physicalFeetPosition());
        return movementResult
                .predictedState()
                .withExternalDisplacement(external)
                .withRelocatedPosition(position, moved.x() * moved.x() + moved.y() * moved.y() + moved.z() * moved.z());
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
