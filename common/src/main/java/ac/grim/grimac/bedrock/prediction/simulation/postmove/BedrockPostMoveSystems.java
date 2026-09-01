package ac.grim.grimac.bedrock.prediction.simulation.postmove;

import ac.grim.grimac.bedrock.prediction.simulation.collision.BedrockCollisionOutput;
import ac.grim.grimac.bedrock.prediction.simulation.collision.BedrockEntityMove;
import ac.grim.grimac.bedrock.prediction.simulation.travel.BedrockMoveRequest;
import ac.grim.grimac.bedrock.prediction.simulation.travel.BedrockTravelPlan;

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
        frame = context.applyWaterJumpGroundReset(frame);
        frame = context.applyBlockMovementSlowdownClear(frame);
        frame = context.resolvePostMoveFluidContext(frame, blockMove.position());
        frame = BedrockPostMoveAutoClimb.apply(context, frame, collision.nextClimbableContact());
        frame = context.applyLiquidDrag(frame);
        frame = context.applyLevitation(frame);
        frame = context.applyGravity(frame);
        frame = context.applyVerticalDrag(frame);
        frame = context.applyStandingSurface(frame, blockMove.position());
        frame = context.applyNormalFriction(frame);
        frame = context.applyPlayerWaterGravity(frame);

        BedrockPostMoveResult result = context.applyLiquidClimbOut(frame, blockMove.position());
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
