package ac.grim.grimac.bedrock.prediction.simulation.postmove;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.grim.grimac.bedrock.prediction.world.BedrockMovementContext;

record BedrockPostMoveFrame(
    Vec3d velocity,
    BedrockCollisionFlags flags,
    BedrockMovementContext postMoveContext,
    boolean standingBounceBounced,
    boolean climbVelocityApplied,
    boolean standingSurfaceHorizontalSlowdownApplied,
    double horizontalFriction
) {
    static BedrockPostMoveFrame initial(
        Vec3d velocity,
        BedrockCollisionFlags flags,
        BedrockMovementContext postMoveContext,
        boolean standingBounceBounced,
        double horizontalFriction
    ) {
        return new BedrockPostMoveFrame(
            velocity,
            flags,
            postMoveContext,
            standingBounceBounced,
            false,
            false,
            horizontalFriction
        );
    }

    BedrockPostMoveFrame withVelocity(Vec3d velocity) {
        return copy(velocity, flags, postMoveContext, standingBounceBounced,
            climbVelocityApplied, standingSurfaceHorizontalSlowdownApplied, horizontalFriction);
    }

    BedrockPostMoveFrame withFlags(BedrockCollisionFlags flags) {
        return copy(velocity, flags, postMoveContext, standingBounceBounced,
            climbVelocityApplied, standingSurfaceHorizontalSlowdownApplied, horizontalFriction);
    }

    BedrockPostMoveFrame withPostMoveContext(BedrockMovementContext postMoveContext) {
        return copy(velocity, flags, postMoveContext, standingBounceBounced,
            climbVelocityApplied, standingSurfaceHorizontalSlowdownApplied, horizontalFriction);
    }

    BedrockPostMoveFrame withVelocityAndClimb(Vec3d velocity, boolean climbVelocityApplied) {
        return copy(velocity, flags, postMoveContext, standingBounceBounced,
            climbVelocityApplied, standingSurfaceHorizontalSlowdownApplied, horizontalFriction);
    }

    BedrockPostMoveFrame withVelocityAndStandingSurfaceSlowdown(
        Vec3d velocity,
        boolean standingSurfaceHorizontalSlowdownApplied
    ) {
        return copy(velocity, flags, postMoveContext, standingBounceBounced, climbVelocityApplied,
            this.standingSurfaceHorizontalSlowdownApplied || standingSurfaceHorizontalSlowdownApplied,
            horizontalFriction);
    }

    BedrockPostMoveFrame withVelocityAndHorizontalFriction(Vec3d velocity, double horizontalFriction) {
        return copy(velocity, flags, postMoveContext, standingBounceBounced,
            climbVelocityApplied, standingSurfaceHorizontalSlowdownApplied, horizontalFriction);
    }

    private static BedrockPostMoveFrame copy(
        Vec3d velocity,
        BedrockCollisionFlags flags,
        BedrockMovementContext context,
        boolean standingBounce,
        boolean climbVelocity,
        boolean surfaceSlowdown,
        double friction
    ) {
        return new BedrockPostMoveFrame(
            velocity, flags, context, standingBounce, climbVelocity, surfaceSlowdown, friction
        );
    }
}
