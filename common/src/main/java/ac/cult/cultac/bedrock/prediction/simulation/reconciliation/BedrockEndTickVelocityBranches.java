package ac.cult.cultac.bedrock.prediction.simulation.reconciliation;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import java.util.ArrayList;
import java.util.List;

final class BedrockEndTickVelocityBranches {
    private static final int MAX_CLIMBABLE_VELOCITIES = 3;

    private BedrockEndTickVelocityBranches() {}

    static List<BedrockMovementState> fromSimulated(BedrockMovementResult movementResult, BedrockMovementState state) {
        ArrayList<BedrockMovementState> states = new ArrayList<>();
        states.add(state);
        List<Double> velocityYBranches = BedrockClimbEndTickVelocityBranches.velocityYBranches(movementResult, state);
        if (velocityYBranches.size() > MAX_CLIMBABLE_VELOCITIES) {
            throw new IllegalStateException("Bedrock end-tick climbable velocities exceeded Java-shaped bound");
        }
        for (double velocityY : velocityYBranches) {
            if (!Double.isFinite(velocityY)) {
                continue;
            }
            Vec3d velocity = new Vec3d(state.velocity().x(), velocityY, state.velocity().z());
            states.add(state.withVelocityAndCollisionFlags(velocity, state.collisionFlags()));
        }
        return List.copyOf(states);
    }
}
