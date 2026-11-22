package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;

final class BedrockValidationSelectedState {
    private BedrockValidationSelectedState() {}

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
}
