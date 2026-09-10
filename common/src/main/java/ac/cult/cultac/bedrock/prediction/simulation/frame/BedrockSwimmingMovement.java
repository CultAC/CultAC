package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.input.BedrockInputIntent;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.state.BedrockSwimmingPoseProgress;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;

public final class BedrockSwimmingMovement {
    private BedrockSwimmingMovement() {
    }

    static SwimmingState initial(
        BedrockMovementState current,
        BedrockMovementContext context
    ) {
        boolean actorStateAtStart = context.actorSwimming() || current.swimming();
        double swimAmount = BedrockSwimmingPoseProgress.nextSwimAmount(
            current.swimAmount(),
            actorStateAtStart
        );
        return new SwimmingState(
            actorStateAtStart,
            actorStateAtStart,
            swimAmount
        );
    }

    public record SwimmingState(
        boolean actorStateAtStart,
        boolean actorStateAfterActions,
        double swimAmount
    ) {
        public boolean nextActorSwimming() {
            return actorStateAfterActions;
        }

        public SwimmingState afterActions(BedrockInputIntent intent, boolean waterContact) {
            boolean actorSwimming = actorStateAtStart;

            if (intent.pose().startSwimming() && waterContact) { // TODO: stop permanent desync from this
                actorSwimming = true;
            }
            if (intent.pose().stopSwimming()) {
                actorSwimming = false;
            }
            return new SwimmingState(
                actorStateAtStart,
                actorSwimming,
                swimAmount
            );
        }
    }
}
