package ac.cult.cultac.bedrock.prediction.simulation.postmove;

import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockGlideState;

final class BedrockGlidePostMoveMovement {
    private BedrockGlidePostMoveMovement() {
    }

    static boolean activeAfterMove(BedrockGlideState gliding, BedrockCollisionFlags flags) {
        return activeAfterMove(gliding, flags, false);
    }

    static boolean activeAfterMove(BedrockGlideState gliding, BedrockCollisionFlags flags, boolean onClimbable) {
        return gliding.activeAfterActions() && !flags.onGround() && !onClimbable;
    }
}
