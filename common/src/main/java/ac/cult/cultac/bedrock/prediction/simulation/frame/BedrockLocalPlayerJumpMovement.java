package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import java.util.Objects;

final class BedrockLocalPlayerJumpMovement {
    private BedrockLocalPlayerJumpMovement() {
    }

    static boolean requestedLaunch(
        BedrockMovementState current,
        BedrockInputFrame frame,
        boolean inWater,
        boolean inLava,
        boolean inScaffolding
    ) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(frame, "frame");
        // START_JUMPING describes the original launch. Holding jump can launch on
        // a different tick after a correction changes when the actor is grounded.
        return frame.jumping()
            && !inWater
            && !inLava
            && !inScaffolding
            && current.collisionFlags().onGround();
    }
}
