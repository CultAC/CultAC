package ac.grim.grimac.bedrock.prediction.simulation.frame;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.input.BedrockInputIntent;
import ac.grim.grimac.bedrock.prediction.state.BedrockMovementState;
import ac.grim.grimac.bedrock.prediction.world.BedrockMovementContext;

final class BedrockGlideInputMovement {
    private BedrockGlideInputMovement() {
    }

    static Vec3d apply(
        Vec3d velocity,
        BedrockMovementState current,
        BedrockInputIntent intent,
        BedrockMovementContext context,
        BedrockGlideState gliding
    ) {

        return BedrockAerialMovement.glideInputSystemVelocity(
            velocity,
            current,
            intent,
            context,
            gliding.activeAtGlideInputSystem()
        );
    }
}
