package ac.cult.cultac.bedrock.prediction.simulation.reconciliation;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockAerialMovement;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockClimbMovement;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import java.util.List;

final class BedrockClimbEndTickVelocityBranches {
    private BedrockClimbEndTickVelocityBranches() {}

    static List<Double> velocityYBranches(BedrockMovementResult movementResult, BedrockMovementState state) {
        if (movementResult.movementContext().inWater()
                || movementResult.movementContext().inLava()) {
            return List.of();
        }
        if (descendingThroughBlockMove(movementResult)) {

            return List.of(BedrockAerialMovement.airDraggedVelocityWithoutGravity(
                    BedrockClimbMovement.SCAFFOLDING_DESCEND_VELOCITY));
        }
        return List.of();
    }

    private static boolean descendingThroughBlockMove(BedrockMovementResult movementResult) {
        double requestedY = movementResult.rawPredictedPhysicalFeetPosition().y()
                - movementResult.previousState().physicalFeetPosition().y();
        double draggedY = BedrockAerialMovement.airDraggedVelocityWithoutGravity(
                BedrockClimbMovement.SCAFFOLDING_DESCEND_VELOCITY);
        return Math.abs(requestedY - BedrockClimbMovement.SCAFFOLDING_DESCEND_VELOCITY) <= 1.0E-9D
                && Math.abs(movementResult.collisionInputVelocity().y() - draggedY) <= 1.0E-9D;
    }
}
