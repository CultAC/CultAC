package ac.cult.cultac.bedrock.prediction.simulation;

import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockCollisionOutput;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockBlockSurfaceMovement;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockFrameFacts;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockLiquidSensing;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockUnderwaterSensing;
import ac.cult.cultac.bedrock.prediction.state.BedrockCameraWaterState;
import ac.cult.cultac.bedrock.prediction.simulation.postmove.BedrockPostMoveResult;
import ac.cult.cultac.bedrock.prediction.simulation.travel.BedrockTravelPlan;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;

final class BedrockFallDistance {
    private BedrockFallDistance() {
    }

    static boolean wasInWaterAfterMove(BedrockTravelPlan plan, BedrockCollisionOutput collision) {
        BedrockFrameFacts facts = plan.frame().frameFacts();
        return facts.inWater() || plan.frame().input().options().travelActive()
            && BedrockLiquidSensing.inWaterFlag(
                facts.context(), collision.blockMove().position(), facts.movementDimensions());
    }

    static BedrockCameraWaterState cameraWaterAfterMove(BedrockTravelPlan plan, BedrockCollisionOutput collision) {
        var frame = plan.frame();
        // A move that started outside water can enter it. Resample at the new
        // position after the camera update, without advancing the camera again.
        return !frame.frameFacts().inWater() && frame.input().options().travelActive()
            ? BedrockUnderwaterSensing.update(frame.cameraWater(), frame.frameFacts().context(),
                collision.blockMove().position(), frame.frameFacts().movementDimensions())
            : frame.cameraWater();
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
