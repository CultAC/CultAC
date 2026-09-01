package ac.grim.grimac.bedrock.prediction.simulation.postmove;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.simulation.frame.BedrockBlockSurfaceMovement;
import ac.grim.grimac.bedrock.prediction.world.BedrockMovementContext;

public final class BedrockEntityInsideMovement {
    private BedrockEntityInsideMovement() {
    }

    public static BedrockPostMoveResult apply(
        BedrockPostMoveContext context,
        BedrockPostMoveResult effects,
        Vec3d nextPosition
    ) {
        Vec3d velocity = effects.velocity();
        Vec3d preDownwardBubbleColumnVelocity = velocity;
        velocity = applyBubbleColumns(
            effects.postMoveContext(),
            context.gliding().activeAtTravelSensing(),
            velocity,
            nextPosition,
            context.current().simulationTick()
        );
        if (context.clearStateVectorAfterSlowdownMove()) {
            velocity = new Vec3d(0.0D, velocity.y(), 0.0D);
        }
        Vec3d preInsideBlockVelocity = velocity;
        velocity = BedrockBlockSurfaceMovement.applyInsideBlockAfterPostMoveEffects(
            velocity,
            context.honeySlideState(),
            nextPosition,
            context.movementDimensions()
        );
        if (context.clearStateVectorAfterSlowdownMove()) {
            velocity = new Vec3d(0.0D, velocity.y(), 0.0D);
        }
        return effects.withEntityInside(
            velocity,
            preDownwardBubbleColumnVelocity,
            preInsideBlockVelocity
        );
    }

    public static Vec3d applyBubbleColumns(
        BedrockMovementContext postMoveContext,
        boolean glidingActive,
        Vec3d velocity,
        Vec3d nextPosition,
        long simulationTick
    ) {
        if (postMoveContext.movementAbilityFlying() || glidingActive) {
            return velocity;
        }
        if (!postMoveContext.worldState().fluidState().bubbleColumnState().layers().isEmpty()) {
            return new Vec3d(
                velocity.x(),
                BedrockBubbleColumnMovement.velocityY(
                    postMoveContext.worldState().fluidState().bubbleColumnState(),
                    velocity.y(),
                    nextPosition.y(),
                    simulationTick
                ),
                velocity.z()
            );
        }
        if (postMoveContext.inUpwardBubbleColumn()) {
            velocity = new Vec3d(
                velocity.x(),
                BedrockUpwardBubbleColumnMovement.velocityY(
                    postMoveContext,
                    velocity.y(),
                    nextPosition.y(),
                    simulationTick
                ),
                velocity.z()
            );
        }
        if (postMoveContext.inDownwardBubbleColumn()) {
            velocity = BedrockDownwardBubbleColumnMovement.velocity(
                postMoveContext.worldState().fluidState().bubbleColumnState(),
                velocity,
                nextPosition.y(),
                simulationTick
            );
        }
        return velocity;
    }
}
