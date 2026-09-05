package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputIntent;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;

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
