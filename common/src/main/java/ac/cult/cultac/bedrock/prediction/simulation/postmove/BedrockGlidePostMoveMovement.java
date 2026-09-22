package ac.cult.cultac.bedrock.prediction.simulation.postmove;

import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockGlideState;

final class BedrockGlidePostMoveMovement {
    private BedrockGlidePostMoveMovement() {
    }

    static boolean activeAfterMove(BedrockGlideState gliding, boolean onClimbable) {
        return gliding.activeAfterActions() && !onClimbable;
    }
}
