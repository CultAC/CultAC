package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputIntent;
import ac.cult.cultac.bedrock.prediction.input.BedrockPoseInputData;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.state.BedrockSwimmingPoseProgress;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;

public final class BedrockSwimmingMovement {
    private BedrockSwimmingMovement() {
    }

    static SwimmingState initial(
        BedrockMovementState current,
        BedrockMovementContext context,
        BedrockInputFrame frame
    ) {
        boolean actorStateAtStart = context.actorSwimming() || current.swimming();
        boolean swimPoseActive = actorStateAtStart
            || BedrockPoseInputData.crawlingBeforeActions(frame, current.horizontalPose());
        double swimAmount = BedrockSwimmingPoseProgress.nextSwimAmount(
            current.swimAmount(),
            swimPoseActive
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

            if ((intent.swimmingRequested() || intent.pose().startSwimming()) && waterContact) {
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
