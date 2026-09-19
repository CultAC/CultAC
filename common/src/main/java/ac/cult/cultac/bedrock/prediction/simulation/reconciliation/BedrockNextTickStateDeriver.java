package ac.cult.cultac.bedrock.prediction.simulation.reconciliation;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class BedrockNextTickStateDeriver {
    private BedrockNextTickStateDeriver() {
    }

    /** Carry a complete forward tick without reconstructing velocity from a packet position. */
    public static List<DerivedState> fromSimulated(BedrockMovementResult result, BedrockMovementState state) {
        if (!result.travelActive() || state.isBoat() || result.selectedGlidingTravel() && state.gliding()) {
            return List.of(new DerivedState(state, state));
        }
        var evidence = new BedrockAcceptedEndpointEvidence(result, state,
                state.physicalFeetPosition().subtract(result.previousState().physicalFeetPosition()),
                state.playerDimensions(), result.postMoveContext(), result.steppedUp(), null);
        return BedrockAcceptedDiffVelocity.withAcceptedEndpointClimbableContacts(result, state, evidence).stream()
                .flatMap(contact -> BedrockEndTickVelocityBranches.fromAcceptedDiff(evidence, contact, state).stream())
                .distinct().toList();
    }

    public static List<DerivedState> withAcceptedDiff(
            BedrockMovementResult movementResult,
            List<BedrockMovementState> sourceStates,
            Vec3d acceptedDiff
    ) {
        return withAcceptedDiff(movementResult, sourceStates, acceptedDiff, false, false);
    }

    public static List<DerivedState> withAcceptedDiff(
            BedrockMovementResult movementResult,
            List<BedrockMovementState> sourceStates,
            Vec3d acceptedDiff,
            boolean selectedXCollision,
            boolean selectedZCollision
    ) {
        return withAcceptedDiff(
                movementResult, sourceStates, acceptedDiff, selectedXCollision, selectedZCollision, false);
    }

    public static List<DerivedState> withAcceptedDiff(
            BedrockMovementResult movementResult,
            List<BedrockMovementState> sourceStates,
            Vec3d acceptedDiff,
            boolean selectedXCollision,
            boolean selectedZCollision,
            boolean canStep
    ) {
        Objects.requireNonNull(movementResult, "movementResult");
        Objects.requireNonNull(sourceStates, "sourceStates");
        Objects.requireNonNull(acceptedDiff, "acceptedDiff");

        // Travel's end-of-tick systems do not run during a teleport or a skipped actor tick.
        if (!movementResult.travelActive()) {
            return sourceStates.stream().map(state -> new DerivedState(state, state)).toList();
        }
        if (movementResult.previousState().isBoat()) {
            // The boat candidate includes its complete input and both friction applications.
            return sourceStates.stream().map(state -> new DerivedState(state, state)).toList();
        }
        ArrayList<DerivedState> states = new ArrayList<>();
        for (BedrockMovementState sourceState : sourceStates) {
            List<BedrockAcceptedDiffVelocity.AcceptedState> acceptedDiffStates = BedrockAcceptedDiffVelocity.apply(
                    movementResult,
                    sourceState,
                    acceptedDiff,
                    selectedXCollision,
                    selectedZCollision,
                    canStep);
            for (BedrockAcceptedDiffVelocity.AcceptedState accepted : acceptedDiffStates) {
                BedrockMovementState acceptedDiffState = accepted.state();
                if (movementResult.selectedGlidingTravel() && acceptedDiffState.gliding()) {
                    states.add(new DerivedState(acceptedDiffState, sourceState));
                    continue;
                }
                states.addAll(BedrockEndTickVelocityBranches.fromAcceptedDiff(
                    accepted.evidence(),
                    acceptedDiffState,
                    sourceState));
            }
        }
        return List.copyOf(states);
    }

    public static double airDraggedVelocityWithGravity(double verticalVelocity) {
        return BedrockAcceptedDiffVelocity.airDraggedVelocityWithGravity(verticalVelocity);
    }

    public record DerivedState(
            BedrockMovementState state,
            BedrockMovementState lineageState
    ) {
        public DerivedState {
            state = Objects.requireNonNull(state, "state");
            lineageState = Objects.requireNonNull(lineageState, "lineageState");
        }
    }
}
