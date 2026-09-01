package ac.grim.grimac.bedrock.prediction.simulation.reconciliation;

import ac.grim.grimac.bedrock.prediction.api.BedrockMovementResult;
import ac.grim.grimac.bedrock.prediction.simulation.frame.BedrockClimbMovement;
import ac.grim.grimac.bedrock.prediction.simulation.frame.BedrockAerialMovement;
import ac.grim.grimac.bedrock.prediction.state.BedrockMovementState;
import ac.grim.grimac.bedrock.prediction.world.BedrockClimbableContact;
import java.util.List;

final class BedrockClimbEndTickVelocityBranches {
    private BedrockClimbEndTickVelocityBranches() {
    }

    static List<Double> velocityYBranches(
        BedrockMovementResult movementResult,
        BedrockMovementState state
    ) {
        if (movementResult.movementContext().inWater() || movementResult.movementContext().inLava()) {
            return List.of();
        }
        if (descendingThroughBlockMove(movementResult)) {

            return List.of(BedrockAerialMovement.airDraggedVelocityWithoutGravity(
                BedrockClimbMovement.SCAFFOLDING_DESCEND_VELOCITY
            ));
        }
        if (!endTickBranchApplies(movementResult, state)) {
            return List.of();
        }

        return List.of(BedrockClimbMovement.LADDER_ASCEND_VELOCITY);
    }

    private static boolean descendingThroughBlockMove(BedrockMovementResult movementResult) {
        double requestedY = movementResult.rawPredictedPhysicalFeetPosition().y()
            - movementResult.previousState().physicalFeetPosition().y();
        double draggedY = BedrockAerialMovement.airDraggedVelocityWithoutGravity(
            BedrockClimbMovement.SCAFFOLDING_DESCEND_VELOCITY
        );
        return Math.abs(requestedY - BedrockClimbMovement.SCAFFOLDING_DESCEND_VELOCITY) <= 1.0E-9D
            && Math.abs(movementResult.collisionInputVelocity().y() - draggedY) <= 1.0E-9D;
    }

    private static boolean endTickBranchApplies(BedrockMovementResult movementResult, BedrockMovementState state) {
        if (movementResult.predictedState().autoClimbTravel()) {
            return true;
        }
        if (!state.collisionFlags().horizontalCollision()) {
            return false;
        }
        BedrockClimbableContact contact = BedrockClimbableContact.fromBlockWorld(
            movementResult.movementContext().worldState().blockCollisionWorld(),
            state.physicalFeetPosition(),
            movementResult.movementContext().playerDimensionsState().width(),
            movementResult.movementContext().playerDimensionsState().height(),
            movementResult.movementContext().equipmentState().leatherBoots()
        );
        return contact.climbing() && !contact.scaffolding();
    }
}
