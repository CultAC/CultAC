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

        // next tick isn't derived after teleport
        if (!movementResult.travelActive()) {
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
