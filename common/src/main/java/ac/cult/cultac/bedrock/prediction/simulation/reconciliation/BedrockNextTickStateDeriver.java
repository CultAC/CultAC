package ac.cult.cultac.bedrock.prediction.simulation.reconciliation;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockClimbableContact;
import java.util.List;

public final class BedrockNextTickStateDeriver {
    private BedrockNextTickStateDeriver() {}

    /** Carry a complete forward tick without reconstructing velocity from a packet position. */
    public static List<BedrockMovementState> fromSimulated(BedrockMovementResult result, BedrockMovementState state) {
        if (!result.travelActive() || state.isBoat() || result.selectedGlidingTravel() && state.gliding()) {
            return List.of(state);
        }
        return endpointClimbableContacts(result, state).stream()
                .flatMap(contact -> BedrockEndTickVelocityBranches.fromSimulated(result, contact).stream())
                .distinct()
                .toList();
    }

    private static List<BedrockMovementState> endpointClimbableContacts(
            BedrockMovementResult movementResult, BedrockMovementState state) {
        BedrockClimbableContact contact = BedrockClimbableContact.fromBlockWorld(
                movementResult.movementContext().worldState().blockCollisionWorld(),
                state.physicalFeetPosition(),
                state.playerDimensions().width(),
                state.playerDimensions().height(),
                movementResult.movementContext().equipmentState().leatherBoots());
        BedrockMovementState endpoint = state.withClimbableContact(contact);
        BedrockClimbableContact carried = movementResult.previousState().climbableContact();
        BedrockClimbableContact currentPositionContact = BedrockClimbableContact.fromBlockWorld(
                movementResult.movementContext().worldState().blockCollisionWorld(),
                movementResult.previousState().physicalFeetPosition(),
                movementResult.previousState().playerDimensions().width(),
                movementResult.previousState().playerDimensions().height(),
                movementResult.movementContext().equipmentState().leatherBoots());
        if (!carried.equals(currentPositionContact)) {

            return List.of(endpoint);
        }
        if (contact.equals(carried)) {
            return List.of(endpoint);
        }

        return List.of(endpoint, state.withClimbableContact(carried));
    }
}
