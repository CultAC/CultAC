package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputIntent;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;

final class BedrockWaterSwimControl {
    private static final double LOOK_DOWN_THRESHOLD = -0.2D;
    private static final double LOOK_DOWN_REDUCER = 0.085D;
    private static final double LOOK_REDUCER = 0.06D;

    private BedrockWaterSwimControl() {
    }

    static boolean applies(BedrockFrameFacts frameFacts, BedrockInputFrame frame) {
        // The vanilla swim-control system includes the water flag, excludes
        // the jumping flag, then gates on the actor-swimming helper.
        return frameFacts.inWater()
            && !frame.jumping()
            && frameFacts.swimming().actorStateAfterActions();
    }

    static Vec3d lookAdjustedVelocity(
        Vec3d velocity,
        BedrockInputFrame frame,
        BedrockInputIntent intent,
        BedrockMovementContext context,
        boolean headInWater
    ) {
        double lookY = BedrockMath.lookDirectionY(frame.pitch());
        double reducer = context.movementAbilityFlying()
            ? 1.3D
            : lookY < LOOK_DOWN_THRESHOLD
            ? LOOK_DOWN_REDUCER
            : LOOK_REDUCER;
        if (zeroesUpwardLookVelocity(lookY, intent, context, headInWater)) {
            return new Vec3d(velocity.x(), 0.0D, velocity.z());
        }
        return lookAdjustedVelocity(velocity, lookY, reducer);
    }

    private static boolean zeroesUpwardLookVelocity(
        double lookY,
        BedrockInputIntent intent,
        BedrockMovementContext context,
        boolean headInWater
    ) {
        if (lookY <= 0.0D) {
            return false;
        }
        if (context.movementAbilityFlying() && BedrockLiquidVerticalMovement.descendInput(intent)) {
            return false;
        }
        return !headInWater;
    }

    private static Vec3d lookAdjustedVelocity(
        Vec3d velocity,
        double lookY,
        double reducer
    ) {
        return new Vec3d(
            velocity.x(),
            velocity.y() + (lookY - velocity.y()) * reducer,
            velocity.z()
        );
    }

}
