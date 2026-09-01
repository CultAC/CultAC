package ac.grim.grimac.bedrock.prediction.simulation.postmove;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.grim.grimac.bedrock.prediction.simulation.frame.BedrockClimbMovement;
import ac.grim.grimac.bedrock.prediction.world.BedrockClimbableContact;

final class BedrockPostMoveAutoClimb {
    private BedrockPostMoveAutoClimb() {
    }

    static BedrockPostMoveFrame apply(
        BedrockPostMoveContext context,
        BedrockPostMoveFrame frame,
        BedrockClimbableContact nextClimbableContact
    ) {
        if (context.gliding().activeAtTravelSensing()) {
            return frame;
        }
        if (!applies(context, frame.flags(), nextClimbableContact)) {
            return frame;
        }
        Vec3d velocity = frame.velocity();
        return frame.withVelocityAndClimb(
            new Vec3d(
                velocity.x(),
                BedrockClimbMovement.LADDER_ASCEND_VELOCITY,
                velocity.z()
            ),
            true
        );
    }

    private static boolean applies(
        BedrockPostMoveContext context,
        BedrockCollisionFlags flags,
        BedrockClimbableContact nextClimbableContact
    ) {
        return !context.climb().inScaffolding()
            && !context.inWater()
            && !context.inLava()
            && !context.intent().jump().start()
            && nextClimbableContact.climbing()

            && flags.horizontalCollision();
    }
}
