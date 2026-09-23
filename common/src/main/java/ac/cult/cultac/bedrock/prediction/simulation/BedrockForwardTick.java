package ac.cult.cultac.bedrock.prediction.simulation;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.simulation.reconciliation.BedrockNextTickStateDeriver;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import java.util.List;

/** Pure movement execution shared by live prediction and historical replay. */
public final class BedrockForwardTick {
    private BedrockForwardTick() { }

    public static List<BedrockSimulation.Candidate> simulate(BedrockSimulation.Input input) {
        if (input.control() == null) throw new IllegalArgumentException("Forward movement requires controls");
        return BedrockSimulation.candidates(input);
    }

    public static List<BedrockMovementState> finish(BedrockMovementResult result, BedrockMovementState selected) {
        return finish(result, selected, null);
    }

    public static List<BedrockMovementState> finish(BedrockMovementResult result, BedrockMovementState selected,
            Vec3d reportedVelocity) {
        return BedrockNextTickStateDeriver.fromSimulated(result, selected).stream()
                .map(BedrockNextTickStateDeriver.DerivedState::state)
                .map(state -> BedrockEndTickBlockPush.apply(result, state, reportedVelocity)).distinct().toList();
    }
}
