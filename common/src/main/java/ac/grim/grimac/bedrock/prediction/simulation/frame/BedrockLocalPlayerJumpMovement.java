package ac.grim.grimac.bedrock.prediction.simulation.frame;

import ac.grim.grimac.bedrock.prediction.input.BedrockInputFrame;
import ac.grim.grimac.bedrock.prediction.input.BedrockInputIntent;
import ac.grim.grimac.bedrock.prediction.state.BedrockMovementState;
import java.util.Objects;

final class BedrockLocalPlayerJumpMovement {
    private BedrockLocalPlayerJumpMovement() {
    }

    static boolean requestedLaunch(
        BedrockMovementState current,
        BedrockInputFrame frame,
        BedrockInputIntent intent,
        boolean inWater,
        boolean inLava,
        boolean inScaffolding
    ) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(frame, "frame");
        return frame.jumping()
            && intent.jump().start()
            && !inWater
            && !inLava
            && !inScaffolding
            && current.collisionFlags().onGround();
    }
}
