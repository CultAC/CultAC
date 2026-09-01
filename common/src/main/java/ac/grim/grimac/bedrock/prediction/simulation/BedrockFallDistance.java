package ac.grim.grimac.bedrock.prediction.simulation;

import ac.grim.grimac.bedrock.prediction.simulation.collision.BedrockCollisionOutput;
import ac.grim.grimac.bedrock.prediction.simulation.frame.BedrockBlockSurfaceMovement;
import ac.grim.grimac.bedrock.prediction.simulation.frame.BedrockFrameFacts;
import ac.grim.grimac.bedrock.prediction.simulation.postmove.BedrockPostMoveResult;
import ac.grim.grimac.bedrock.prediction.simulation.travel.BedrockTravelPlan;
import ac.grim.grimac.bedrock.prediction.state.BedrockMovementState;

final class BedrockFallDistance {
    private BedrockFallDistance() {
    }

    static float afterMove(
        BedrockTravelPlan plan,
        BedrockCollisionOutput collision,
        BedrockPostMoveResult postMove
    ) {
        BedrockMovementState current = plan.frame().input().previousState();
        BedrockFrameFacts facts = plan.frame().frameFacts();
        double resolvedY = collision.blockMove().position().y() - current.physicalFeetPosition().y();
        boolean reset = postMove.flags().onGround()
            || facts.climb().climbing()
            || collision.nextClimbableContact().climbing()
            || facts.blockMovementSlowdownState().active()
            || postMove.pendingBlockMovementSlowdownState().active()
            || postMove.postMoveContext().inWater()
            || postMove.postMoveContext().inUpwardBubbleColumn()
            || postMove.postMoveContext().inDownwardBubbleColumn()
            || facts.effectState().slowFalling()
            || facts.effectState().levitationLevel() > 0
            || plan.frame().branch().playerFlyingTravel()
            || BedrockBlockSurfaceMovement.isHoneySliding(
                current.velocity(),
                facts.honeySlideState(),
                current.physicalFeetPosition(),
                facts.movementDimensions()
            );
        return update(current.fallDistance(), resolvedY, reset, postMove.postMoveContext().inLava());
    }

    static float update(float current, double resolvedY, boolean reset, boolean inLava) {

        if (reset) {
            return 0.0F;
        }
        float next = current;
        if (resolvedY < 0.0D) {
            next -= (float) resolvedY;
        }
        // Lava halves accumulated fall distance instead of clearing it.
        return inLava ? next * 0.5F : next;
    }
}
