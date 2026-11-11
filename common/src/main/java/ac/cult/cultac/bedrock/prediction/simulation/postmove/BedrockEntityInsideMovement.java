package ac.cult.cultac.bedrock.prediction.simulation.postmove;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockBlockSurfaceMovement;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;

public final class BedrockEntityInsideMovement {
    private BedrockEntityInsideMovement() {}

    public static BedrockPostMoveResult apply(
            BedrockPostMoveContext context, BedrockPostMoveResult effects, Vec3d nextPosition) {
        InsideVelocity inside = applyVelocity(context, effects, nextPosition, effects.velocity());
        return effects.withEntityInside(inside.velocity(), effects.velocity(), inside.preInsideBlockVelocity());
    }

    private static InsideVelocity applyVelocity(
            BedrockPostMoveContext context, BedrockPostMoveResult effects, Vec3d nextPosition, Vec3d velocity) {
        velocity = applyBubbleColumns(
                effects.postMoveContext(), context.gliding().activeAtTravelSensing(), velocity);
        if (context.clearVelocityAfterSlowdownMove()) {
            velocity = new Vec3d(0.0D, velocity.y(), 0.0D);
        }
        Vec3d preInsideBlockVelocity = velocity;
        velocity = BedrockBlockSurfaceMovement.applyInsideBlockAfterPostMoveEffects(
                velocity, context.honeySlideState(), nextPosition, context.movementDimensions());
        if (context.clearVelocityAfterSlowdownMove()) {
            velocity = new Vec3d(0.0D, velocity.y(), 0.0D);
        }
        return new InsideVelocity(velocity, preInsideBlockVelocity);
    }

    private record InsideVelocity(Vec3d velocity, Vec3d preInsideBlockVelocity) {}

    public static Vec3d applyBubbleColumns(
            BedrockMovementContext postMoveContext, boolean glidingActive, Vec3d velocity) {
        if (postMoveContext.movementAbilityFlying() || glidingActive) {
            return velocity;
        }
        return new Vec3d(
                velocity.x(),
                BedrockBubbleColumnMovement.velocityY(
                        postMoveContext.worldState().fluidState().bubbleColumnState(), velocity.y()),
                velocity.z());
    }
}
