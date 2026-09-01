package ac.grim.grimac.bedrock.prediction.simulation.frame;

import ac.grim.grimac.bedrock.prediction.input.BedrockInputIntent;
import ac.grim.grimac.bedrock.prediction.state.BedrockMovementState;
import ac.grim.grimac.bedrock.prediction.state.BedrockSwimmingPoseProgress;
import ac.grim.grimac.bedrock.prediction.world.BedrockMovementContext;

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

    static SwimmingState duringSpin(BedrockMovementState current) {

        return new SwimmingState(
            false,
            false,
            BedrockSwimmingPoseProgress.nextSwimAmount(current.swimAmount(), false)
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

        public SwimmingState afterActions(BedrockInputIntent intent) {
            boolean actorSwimming = actorStateAtStart;

            if (intent.pose().startSwimming()) {
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
