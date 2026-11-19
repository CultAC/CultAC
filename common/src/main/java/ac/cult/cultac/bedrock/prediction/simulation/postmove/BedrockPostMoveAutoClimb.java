package ac.cult.cultac.bedrock.prediction.simulation.postmove;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockClimbMovement;
import ac.cult.cultac.bedrock.prediction.world.BedrockClimbableContact;

final class BedrockPostMoveAutoClimb {
    private BedrockPostMoveAutoClimb() {}

    static BedrockPostMoveFrame apply(
            BedrockPostMoveContext context, BedrockPostMoveFrame frame, BedrockClimbableContact nextClimbableContact) {
        if (context.gliding().activeAtTravelSensing()) {
            return frame;
        }
        if (!applies(context, frame.flags(), nextClimbableContact)) {
            return frame;
        }
        Vec3d velocity = frame.velocity();
        return frame.withVelocityAndClimb(
                new Vec3d(velocity.x(), BedrockClimbMovement.LADDER_ASCEND_VELOCITY, velocity.z()), true);
    }

    private static boolean applies(
            BedrockPostMoveContext context, BedrockCollisionFlags flags, BedrockClimbableContact nextClimbableContact) {
        // Only the climbable block the centre of the feet is in starts the climb. Scaffolding is
        // not one: it is ascended by jumping. Scaffolding the box reaches beside that block does
        // not stop the climb either. A jump started this tick does not stop it: the climb replaces
        // the jump's velocity after the move.
        return !context.inWater() && !context.inLava() && nextClimbableContact.ladder() && flags.horizontalCollision();
    }
}
