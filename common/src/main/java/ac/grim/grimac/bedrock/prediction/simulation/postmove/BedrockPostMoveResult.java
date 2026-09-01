package ac.grim.grimac.bedrock.prediction.simulation.postmove;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.grim.grimac.bedrock.prediction.model.BlockMovementSlowdownState;
import ac.grim.grimac.bedrock.prediction.model.Medium;
import ac.grim.grimac.bedrock.prediction.world.BedrockMovementContext;

public record BedrockPostMoveResult(
    VelocityEffects velocityEffects,
    CollisionEffects collisionEffects,
    StateModes stateModes
) {
    private static final double VELOCITY_EPSILON = 1.0E-12D;

    public static BedrockPostMoveResult afterLiquidClimbOut(
        BedrockPostMoveFrame frame,
        Vec3d velocity,
        BedrockCollisionFlags flags,
        double horizontalFriction
    ) {
        return new BedrockPostMoveResult(
            new VelocityEffects(velocity, velocity, velocity,
                frame.standingSurfaceHorizontalSlowdownApplied(), false, false, horizontalFriction),
            new CollisionEffects(flags, frame.postMoveContext(), frame.standingBounceBounced(),
                frame.climbVelocityApplied(), BlockMovementSlowdownState.NONE),
            new StateModes(false, Medium.AIR, false)
        );
    }

    public Vec3d velocity() { return velocityEffects.velocity(); }
    public Vec3d preDownwardBubbleColumnVelocity() { return velocityEffects.preDownwardBubbleColumnVelocity(); }
    public Vec3d preInsideBlockVelocity() { return velocityEffects.preInsideBlockVelocity(); }
    public boolean standingSurfaceHorizontalSlowdownApplied() { return velocityEffects.standingSurfaceHorizontalSlowdownApplied(); }
    public boolean orderedPostMoveOwnsHorizontalVelocity() { return velocityEffects.ownsHorizontalVelocity(); }
    public boolean orderedPostMoveOwnsVerticalVelocity() { return velocityEffects.ownsVerticalVelocity(); }
    public double horizontalFriction() { return velocityEffects.horizontalFriction(); }
    public BedrockCollisionFlags flags() { return collisionEffects.flags(); }
    public BedrockMovementContext postMoveContext() { return collisionEffects.postMoveContext(); }
    public boolean standingBounceBounced() { return collisionEffects.standingBounceBounced(); }
    public boolean climbVelocityApplied() { return collisionEffects.climbVelocityApplied(); }
    public BlockMovementSlowdownState pendingBlockMovementSlowdownState() { return collisionEffects.pendingBlockMovementSlowdownState(); }
    public boolean waterTravelActive() { return stateModes.waterTravelActive(); }
    public Medium movementBranch() { return stateModes.movementBranch(); }
    public boolean gliding() { return stateModes.gliding(); }

    public BedrockPostMoveResult withEntityInside(
        Vec3d velocity,
        Vec3d preDownwardBubbleColumnVelocity,
        Vec3d preInsideBlockVelocity
    ) {
        return new BedrockPostMoveResult(
            new VelocityEffects(
                velocity,
                preDownwardBubbleColumnVelocity,
                preInsideBlockVelocity,
                standingSurfaceHorizontalSlowdownApplied(),
                horizontalVelocityChanged(preInsideBlockVelocity, velocity),
                verticalVelocityChanged(preDownwardBubbleColumnVelocity, velocity)
                    || verticalVelocityChanged(preInsideBlockVelocity, velocity),
                horizontalFriction()
            ),
            collisionEffects,
            stateModes
        );
    }

    public BedrockPostMoveResult withPendingBlockMovementSlowdownState(BlockMovementSlowdownState state) {
        return new BedrockPostMoveResult(
            velocityEffects,
            collisionEffects.withPendingBlockMovementSlowdownState(state),
            stateModes
        );
    }

    public BedrockPostMoveResult withStateModes(boolean waterTravel, Medium movementBranch, boolean gliding) {
        return new BedrockPostMoveResult(
            velocityEffects,
            collisionEffects,
            new StateModes(waterTravel, movementBranch, gliding)
        );
    }

    private static boolean horizontalVelocityChanged(Vec3d before, Vec3d after) {
        return Math.abs(before.x() - after.x()) > VELOCITY_EPSILON
            || Math.abs(before.z() - after.z()) > VELOCITY_EPSILON;
    }

    private static boolean verticalVelocityChanged(Vec3d before, Vec3d after) {
        return Math.abs(before.y() - after.y()) > VELOCITY_EPSILON;
    }

    public record VelocityEffects(
        Vec3d velocity,
        Vec3d preDownwardBubbleColumnVelocity,
        Vec3d preInsideBlockVelocity,
        boolean standingSurfaceHorizontalSlowdownApplied,
        boolean ownsHorizontalVelocity,
        boolean ownsVerticalVelocity,
        double horizontalFriction
    ) {
    }

    public record CollisionEffects(
        BedrockCollisionFlags flags,
        BedrockMovementContext postMoveContext,
        boolean standingBounceBounced,
        boolean climbVelocityApplied,
        BlockMovementSlowdownState pendingBlockMovementSlowdownState
    ) {
        CollisionEffects withPendingBlockMovementSlowdownState(BlockMovementSlowdownState state) {
            return new CollisionEffects(
                flags, postMoveContext, standingBounceBounced, climbVelocityApplied, state
            );
        }
    }

    public record StateModes(boolean waterTravelActive, Medium movementBranch, boolean gliding) {
    }
}
