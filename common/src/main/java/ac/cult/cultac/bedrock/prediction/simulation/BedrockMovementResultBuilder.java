package ac.cult.cultac.bedrock.prediction.simulation;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.BlockMovementSlowdownState;
import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockCollisionOutput;
import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockEntityMove;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockFrameFacts;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockFrameState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockTravelInputControl;
import ac.cult.cultac.bedrock.prediction.simulation.postmove.BedrockPostMoveResult;
import ac.cult.cultac.bedrock.prediction.simulation.travel.BedrockMoveRequest;
import ac.cult.cultac.bedrock.prediction.simulation.travel.BedrockTravelPlan;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementUpdate;
import ac.cult.cultac.bedrock.prediction.world.BedrockClimbableContact;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.world.HoneySlideState;

final class BedrockMovementResultBuilder {
    private BedrockMovementResultBuilder() {
    }

    static BedrockMovementResult withoutActorMovementTick(
        BedrockMovementState current,
        BedrockInputFrame frame,
        BedrockWorldSnapshot snapshot,
        boolean canStep,
        double maxUpStep
    ) {
        BedrockMovementState next = current.withoutActorMovementTick(frame);
        return new BedrockMovementResult(
            current,
            snapshot.movementContext(),
            snapshot.movementContext(),
            next,
            current.physicalFeetPosition(),
            Vec3d.ZERO,
            false,
            false,
            false,
            false,
            BlockMovementSlowdownState.NONE,
            HoneySlideState.NONE,
            current.gliding(),
            current.waterTravelFlag(),
            0.0D,
            1.0D,
            false,
            false,
            canStep,
            maxUpStep);
    }

    static BedrockMovementResult build(
        BedrockTravelPlan plan,
        BedrockCollisionOutput collision,
        BedrockPostMoveResult postMoveEffects
    ) {
        BedrockFrameState state = plan.frame();
        BedrockMovementState current = state.input().previousState();
        BedrockInputFrame frame = state.input().inputFrame();
        BedrockFrameFacts frameFacts = state.frameFacts();
        BedrockMoveRequest moveRequest = collision.moveRequest();
        BedrockEntityMove.Result blockMove = collision.blockMove();
        BedrockClimbableContact nextClimbableContact = collision.nextClimbableContact();
        BedrockTravelInputControl.InputControlState control = state.control();
        BedrockMovementState next = current.advance(new BedrockMovementUpdate(
            blockMove.position(),
            postMoveEffects.velocity(),
            frame,
            postMoveEffects.flags(),
            frameFacts.boundingBoxMode(),
            frameFacts.movementDimensions(),
            BedrockFallDistance.afterMove(plan, collision, postMoveEffects),
            frameFacts.powderSnowTicks(),
            new BedrockMovementUpdate.Glide(
                postMoveEffects.gliding(),
                state.gliding().requestAfterActions() && postMoveEffects.gliding()),
            frameFacts.swimming().nextActorSwimming(),
            frameFacts.swimming().swimAmount(),
            new BedrockMovementUpdate.Riptide(
                state.riptide().nextChargeTicks(),
                state.riptide().spinActive(),
                state.riptide().spinTicks()),
            new BedrockMovementUpdate.ItemUse(
                control.itemUseSlowdownActive(), control.itemUseSlowdownTicks())
        )).withClimbableContact(nextClimbableContact)
            .withAutoClimbTravel(postMoveEffects.climbVelocityApplied())
            .withPendingBlockMovementSlowdownState(postMoveEffects.pendingBlockMovementSlowdownState())
            .withWasInWaterFlag(frameFacts.inWater())
            .withWaterTravelFlag(postMoveEffects.waterTravelActive())
            .withMovementBranch(postMoveEffects.movementBranch());
        return new BedrockMovementResult(
            current,
            frameFacts.context(),
            postMoveEffects.postMoveContext(),
            next,
            moveRequest.requestedPosition(),
            moveRequest.collisionInputVelocity(),
            postMoveEffects.orderedPostMoveOwnsHorizontalVelocity(),
            postMoveEffects.orderedPostMoveOwnsVerticalVelocity(),
            postMoveEffects.standingBounceBounced(),
            postMoveEffects.standingSurfaceHorizontalSlowdownApplied(),
            frameFacts.blockMovementSlowdownState(),
            frameFacts.honeySlideState(),
            state.branch().glidingTravel(),
            state.branch().waterTravel(),
            horizontalInputLimit(plan),
            postMoveEffects.horizontalFriction(),
            blockMove.steppedUp(),
            blockMove.stepRetryAllowed(),
            state.input().options().canStep(),
            state.input().options().maxUpStep()
        );
    }

    private static double horizontalInputLimit(BedrockTravelPlan plan) {

        double limit = plan.horizontal().horizontalInputLimit();
        double moveInputScale = plan.frame().control().moveInputScale();
        if (moveInputScale > 0.0D) {
            limit /= moveInputScale;
        }
        BlockMovementSlowdownState blockMovementSlowdown = plan.frame().frameFacts().blockMovementSlowdownState();
        if (!blockMovementSlowdown.active()) {
            return limit;
        }
        return limit * Math.min(blockMovementSlowdown.xMultiplier(), blockMovementSlowdown.zMultiplier());
    }
}
