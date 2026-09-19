package ac.cult.cultac.bedrock.prediction.simulation.postmove;

import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockCollisionOutput;
import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockEntityMove;
import ac.cult.cultac.bedrock.prediction.simulation.travel.BedrockMoveRequest;
import ac.cult.cultac.bedrock.prediction.simulation.travel.BedrockTravelPlan;

public final class BedrockPostMoveSystems {
    private BedrockPostMoveSystems() {
    }

    public static BedrockPostMoveResult postMove(BedrockTravelPlan plan, BedrockCollisionOutput collision) {
        BedrockPostMoveContext context = context(plan, collision.moveRequest());
        BedrockEntityMove.Result blockMove = collision.blockMove();

        BedrockPostMoveFrame frame = context.startEffectFrame(
            blockMove.velocity(),
            blockMove.collisionFlags(),
            false
        );
        if (plan.frame().boat() != null) {
            frame = context.applyBlockMovementSlowdownClear(frame);
            frame = context.resolvePostMoveFluidContext(frame, blockMove.position());
            var velocity = ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockBoatMovement.afterMove(
                    plan.frame().input().previousState(), plan.frame().frameFacts().blockCollisionWorld(),
                    blockMove.position(), frame.velocity());
            velocity = BedrockBoatInsideMovement.apply(frame.postMoveContext(), velocity,
                    blockMove.position(), plan.frame().input().previousState().simulationTick());
            velocity = ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockBlockSurfaceMovement.applyInsideBlockAfterPostMoveEffects(
                    velocity, plan.frame().frameFacts().honeySlideState(), blockMove.position(),
                    plan.frame().frameFacts().movementDimensions());
            var result = BedrockPostMoveResult.afterLiquidClimbOut(frame, velocity, frame.flags(), 1.0D);
            return new BedrockPostMoveResult(new BedrockPostMoveResult.VelocityEffects(
                    velocity, velocity, velocity, false, true, true, 1.0D), result.collisionEffects(), result.stateModes());
        }
        boolean travel = plan.frame().input().options().travelActive();
        if (travel) {
            frame = context.applyWaterJumpGroundReset(frame);
            frame = context.applyBlockMovementSlowdownClear(frame);
        }
        frame = context.resolvePostMoveFluidContext(frame, blockMove.position());
        if (travel) {
            frame = BedrockPostMoveAutoClimb.apply(context, frame, collision.nextClimbableContact());
            frame = context.applyLiquidDrag(frame);
            frame = context.applyLevitation(frame);
            frame = context.applyGravity(frame);
            frame = context.applyVerticalDrag(frame);
            frame = context.applyStandingSurface(frame, blockMove.position());
            frame = context.applyNormalFriction(frame);
            frame = context.applyPlayerWaterGravity(frame);
        }

        BedrockPostMoveResult result = travel
            ? context.applyLiquidClimbOut(frame, blockMove.position())
            : BedrockPostMoveResult.afterLiquidClimbOut(frame, frame.velocity(), frame.flags(), 1.0D);
        result = BedrockEntityInsideMovement.apply(context, result, blockMove.position());
        result = result.withPendingBlockMovementSlowdownState(
            context.resolvePendingBlockMovementSlowdown(blockMove.position())
        );
        result = context.withPostMoveStateModes(result, blockMove.position());
        return result;
    }

    private static BedrockPostMoveContext context(BedrockTravelPlan plan, BedrockMoveRequest moveRequest) {
        // The vanilla sprint action runs after movement input, while water
        // drag reads the resulting actor flag.
        return new BedrockPostMoveContext(plan, moveRequest);
    }
}
