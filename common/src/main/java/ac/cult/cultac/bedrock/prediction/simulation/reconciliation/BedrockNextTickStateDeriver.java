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
        // The next tick climbs by the blocks the box touches where this tick's move left it.
        BedrockMovementState endpoint = state.withClimbableContact(BedrockClimbableContact.fromBlockWorld(
                result.movementContext().worldState().blockCollisionWorld(),
                state.physicalFeetPosition(),
                state.playerDimensions().width(),
                state.playerDimensions().height(),
                result.movementContext().equipmentState().leatherBoots()));
        return BedrockEndTickVelocityBranches.fromSimulated(result, endpoint);
    }
}
