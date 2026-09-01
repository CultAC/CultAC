package ac.grim.grimac.bedrock.prediction.simulation.postmove;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.input.BedrockInputFrame;
import ac.grim.grimac.bedrock.prediction.input.BedrockInputIntent;
import ac.grim.grimac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.grim.grimac.bedrock.prediction.model.BedrockEffectState;
import ac.grim.grimac.bedrock.prediction.model.BlockMovementSlowdownState;
import ac.grim.grimac.bedrock.prediction.model.Medium;
import ac.grim.grimac.bedrock.prediction.model.PlayerDimensionsState;
import ac.grim.grimac.bedrock.prediction.simulation.frame.BedrockBlockMovementSlowdownResolver;
import ac.grim.grimac.bedrock.prediction.simulation.frame.BedrockBlockSurfaceMovement;
import ac.grim.grimac.bedrock.prediction.simulation.frame.BedrockClimbState;
import ac.grim.grimac.bedrock.prediction.simulation.frame.BedrockFluidStateResolver;
import ac.grim.grimac.bedrock.prediction.simulation.frame.BedrockGlideState;
import ac.grim.grimac.bedrock.prediction.simulation.frame.BedrockMobJump;
import ac.grim.grimac.bedrock.prediction.simulation.frame.BedrockTravelTypeResolver;
import ac.grim.grimac.bedrock.prediction.simulation.travel.BedrockMoveRequest;
import ac.grim.grimac.bedrock.prediction.simulation.travel.BedrockTravelHorizontalControl;
import ac.grim.grimac.bedrock.prediction.simulation.travel.BedrockTravelPlan;
import ac.grim.grimac.bedrock.prediction.state.BedrockMovementState;
import ac.grim.grimac.bedrock.prediction.world.BedrockMovementContext;
import ac.grim.grimac.bedrock.prediction.world.BlockCollisionWorld;
import ac.grim.grimac.bedrock.prediction.world.HoneySlideState;

record BedrockPostMoveContext(
    BedrockTravelPlan plan,
    BedrockMoveRequest moveRequest
) {
    private static final double VELOCITY_EPSILON = 1.0E-12D;

    BedrockMovementState current() { return plan.frame().input().previousState(); }
    BedrockMovementContext context() { return plan.frame().frameFacts().context(); }
    BedrockEffectState effectState() { return plan.frame().frameFacts().effectState(); }
    PlayerDimensionsState movementDimensions() { return plan.frame().frameFacts().movementDimensions(); }
    BlockCollisionWorld blockCollisionWorld() { return plan.frame().frameFacts().blockCollisionWorld(); }
    HoneySlideState honeySlideState() { return plan.frame().frameFacts().honeySlideState(); }
    BedrockInputFrame frame() { return plan.frame().input().inputFrame(); }
    BedrockInputIntent intent() { return plan.frame().inputIntent(); }
    Vec3d startingVelocity() { return plan.frame().input().startingVelocity(); }
    double horizontalFriction() { return plan.horizontal().horizontalFriction(); }
    boolean actorSprinting() { return plan.frame().postMoveActorSprinting(); }
    boolean inWater() { return plan.frame().branch().waterTravel(); }
    boolean inLava() { return plan.frame().branch().lavaTravel(); }
    boolean playerFlying() { return plan.frame().branch().playerFlyingTravel(); }
    boolean navigationCanWalkInLava() { return plan.frame().frameFacts().navigationCanWalkInLava(); }
    boolean swimmingActorStateAfterAction() { return plan.frame().frameFacts().swimming().actorStateAfterActions(); }
    BedrockClimbState climb() { return plan.frame().frameFacts().climb(); }
    BedrockGlideState gliding() { return plan.frame().gliding(); }
    boolean clearStateVectorAfterSlowdownMove() { return plan.frame().frameFacts().blockMovementSlowdownState().clearVelocityAfterMove(); }
    boolean liquidTravelActive() { return plan.frame().branch().selection().liquidTravelActive(); }
    BedrockMobJump mobJump() { return plan.frame().mobJump(); }
    boolean lavaSwimUpApplied() { return moveRequest.lavaSwimUpApplied(); }

    BedrockPostMoveFrame startEffectFrame(
        Vec3d velocity,
        BedrockCollisionFlags flags,
        boolean standingBounceBounced
    ) {
        return BedrockPostMoveFrame.initial(
            velocity,
            flags,
            context(),
            standingBounceBounced,
            horizontalFriction()
        );
    }

    BedrockPostMoveFrame applyWaterJumpGroundReset(BedrockPostMoveFrame frame) {
        if (mobJump().zeroedWaterVelocity()) {
            return frame.withFlags(frame.flags().withOnGround(false));
        }
        return frame;
    }

    BedrockPostMoveFrame applyStandingSurface(BedrockPostMoveFrame frame, Vec3d nextPosition) {
        if (postMoveVelocityEffectsSuppressed()) {
            return frame;
        }
        Vec3d velocity = BedrockBlockSurfaceMovement.applyStandingAfterMove(
            frame.velocity(),
            nextPosition,
            frame().sneaking(),
            frame.flags().onGround(),
            blockCollisionWorld(),
            movementDimensions()
        );
        return frame.withVelocityAndStandingSurfaceSlowdown(
            velocity,
            horizontalVelocityChanged(frame.velocity(), velocity)
        );
    }

    BedrockPostMoveFrame applyBlockMovementSlowdownClear(BedrockPostMoveFrame frame) {
        if (clearStateVectorAfterSlowdownMove()) {
            return frame.withVelocity(Vec3d.ZERO);
        }
        return frame;
    }

    BedrockPostMoveFrame resolvePostMoveFluidContext(BedrockPostMoveFrame frame, Vec3d nextPosition) {
        BedrockMovementContext postMoveContext = BedrockFluidStateResolver.withCurrentTickFluidStateFromBlockWorld(
            context(),
            nextPosition,
            movementDimensions()
        );
        BedrockPostMoveFrame next = frame.withPostMoveContext(postMoveContext);
        if (postMoveContext.inWater()
            || postMoveContext.inLava()
            || postMoveContext.liquidMovementMedium() == Medium.LAVA) {
            return next.withFlags(next.flags().withOnGround(false));
        }
        return next;
    }

    BedrockPostMoveFrame applyLiquidDrag(BedrockPostMoveFrame frame) {
        if (postMoveVelocityEffectsSuppressed()) {
            return frame.withVelocity(frame.velocity());
        }
        boolean gravityAndVerticalDragApplies = gravityAndVerticalDragApplies(frame);
        BedrockPostMoveVerticalEffects.DragResult drag = BedrockPostMoveVerticalEffects.applyLiquidDrag(
            this,
            frame.postMoveContext(),
            frame.velocity(),
            gravityAndVerticalDragApplies
        );
        return frame.withVelocityAndHorizontalFriction(drag.velocity(), drag.horizontalFriction());
    }

    BedrockPostMoveFrame applyLevitation(BedrockPostMoveFrame frame) {
        if (postMoveVelocityEffectsSuppressed() || !gravityAndVerticalDragApplies(frame)) {
            return frame;
        }
        return frame.withVelocity(
            BedrockPostMoveVerticalEffects.applyLevitation(
                this,
                frame.postMoveContext(),
                frame.velocity()
            )
        );
    }

    double waterDragHorizontalFriction(BedrockMovementContext effectContext) {
        // The vanilla water-drag system reads the ordered sprinting actor
        // flag state.
        return BedrockTravelHorizontalControl.waterHorizontalDrag(
            effectContext,
            actorSprinting()
        );
    }

    BedrockPostMoveFrame applyGravity(BedrockPostMoveFrame frame) {
        if (postMoveVelocityEffectsSuppressed() || !gravityAndVerticalDragApplies(frame)) {
            return frame;
        }
        return frame.withVelocity(
            BedrockPostMoveVerticalEffects.applyGravity(
                this,
                frame.postMoveContext(),
                frame.velocity()
            )
        );
    }

    BedrockPostMoveFrame applyVerticalDrag(BedrockPostMoveFrame frame) {
        if (postMoveVelocityEffectsSuppressed() || !gravityAndVerticalDragApplies(frame)) {
            return frame;
        }
        return frame.withVelocity(
            BedrockPostMoveVerticalEffects.applyVerticalDrag(
                this,
                frame.postMoveContext(),
                frame.velocity()
            )
        );
    }

    BedrockPostMoveFrame applyNormalFriction(BedrockPostMoveFrame frame) {
        if (postMoveVelocityEffectsSuppressed()) {
            return frame;
        }
        // The vanilla auto-climb flag excludes vertical drag/gravity systems,
        // but normal horizontal friction still consumes the travel friction.
        double normalFriction = frame.horizontalFriction();
        return frame.withVelocityAndHorizontalFriction(BedrockPostMoveVerticalEffects.applyNormalFriction(
            this,
            frame.postMoveContext(),
            frame.velocity(),
            normalFriction
        ), normalFriction);
    }

    BedrockPostMoveFrame applyPlayerWaterGravity(BedrockPostMoveFrame frame) {
        if (postMoveVelocityEffectsSuppressed() || !gravityAndVerticalDragApplies(frame)) {
            return frame;
        }
        return frame.withVelocity(
            BedrockPostMoveVerticalEffects.applyPlayerWaterGravity(
                this,
                frame.postMoveContext(),
                frame.velocity()
            )
        );
    }

    BedrockPostMoveResult applyLiquidClimbOut(BedrockPostMoveFrame frame, Vec3d nextPosition) {
        boolean liquidClimbOutActive = liquidTravelActive();
        BedrockLiquidClimbOutMovement.Result liquidClimbOut = BedrockLiquidClimbOutMovement.apply(
            liquidClimbOutActive,
            current().physicalFeetPosition(),
            nextPosition,
            frame.velocity(),
            frame.flags(),
            blockCollisionWorld(),
            movementDimensions()
        );
        Vec3d velocity = liquidClimbOut.velocity();
        return BedrockPostMoveResult.afterLiquidClimbOut(
            frame,
            velocity,
            liquidClimbOut.flags(),
            frame.horizontalFriction()
        );
    }

    BlockMovementSlowdownState resolvePendingBlockMovementSlowdown(Vec3d nextPosition) {
        return BedrockBlockMovementSlowdownResolver.nextState(
            context(),
            nextPosition,
            movementDimensions()
        );
    }

    BedrockPostMoveResult withPostMoveStateModes(BedrockPostMoveResult result, Vec3d nextPosition) {
        boolean gliding = BedrockGlidePostMoveMovement.activeAfterMove(
            gliding(),
            result.flags(),
            climb().climbing()
        );
        boolean waterTravelActive = BedrockTravelTypeResolver.waterActive(
            result.postMoveContext(),
            nextPosition,
            movementDimensions());
        return result.withStateModes(
            waterTravelActive,
            BedrockTravelTypeResolver.postMoveBranch(result.postMoveContext(), result.flags(), waterTravelActive),
            gliding
        );
    }

    private boolean gravityAndVerticalDragApplies(BedrockPostMoveFrame frame) {
        return !frame.standingBounceBounced() && !frame.climbVelocityApplied();
    }

    private boolean postMoveVelocityEffectsSuppressed() {
        return gliding().activeAtTravelSensing();
    }

    private static boolean horizontalVelocityChanged(Vec3d before, Vec3d after) {
        return Math.abs(before.x() - after.x()) > VELOCITY_EPSILON
            || Math.abs(before.z() - after.z()) > VELOCITY_EPSILON;
    }
}
