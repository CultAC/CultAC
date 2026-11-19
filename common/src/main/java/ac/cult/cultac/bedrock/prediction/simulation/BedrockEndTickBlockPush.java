package ac.cult.cultac.bedrock.prediction.simulation;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockBlockSurfaceMovement;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldStageBuilder;
import ac.cult.cultac.utils.math.Vec3;

/**
 * Pushes the velocity a tick ends with out of the blocks its box overlaps, after travel and the
 * end-of-tick velocity effects. The push only raises a slower axis to its speed, it never adds to a faster one.
 * A box no more than one float step inside a block only touches it and is not pushed. A door, trapdoor or fence gate
 * whose cell the box reaches does not push either, see {@link BedrockOneWayBoxes}.
 */
final class BedrockEndTickBlockPush {
    private static final float PUSH_SPEED = 0.1F;
    private static final float MIN_DIRECTION_LENGTH = 1.0E-4F;

    private BedrockEndTickBlockPush() {}

    static BedrockMovementState apply(BedrockMovementResult movement, BedrockMovementState state) {
        return apply(movement, state, BedrockBlockSurfaceMovement.NO_STANDING_SLOWDOWN);
    }

    /**
     * The scale the honey and slime slowdown took the horizontal velocity of {@code state} by. That slowdown takes the
     * pushed velocity, so the push it follows moves a slowed axis by the slowed push.
     */
    static float standingSlowdown(BedrockMovementResult movement, BedrockMovementState state) {
        if (!movement.travelActive() || state.isVehicle()) return BedrockBlockSurfaceMovement.NO_STANDING_SLOWDOWN;
        return BedrockBlockSurfaceMovement.standingSlowdownScale(
                state.velocity(),
                state.physicalFeetPosition(),
                false,
                state.inputFrame().sneaking(),
                state.collisionFlags().onGround(),
                movement.movementContext().worldState().blockCollisionWorld(),
                state.playerDimensions());
    }

    static BedrockMovementState apply(
            BedrockMovementResult movement, BedrockMovementState state, float standingSlowdown) {
        if (!movement.travelActive() || state.isVehicle()) return state;
        // The client tests the float box its move ended with, not one rebuilt around its reported position,
        // against the block shapes in the same float coordinates its collision uses.
        var world = movement.movementContext().worldState().blockCollisionWorld();
        var frame = world.coordinateFrame();
        var end = state.collisionBox(movement.movementContext().playerDimensionsState());
        var box = BedrockOneWayBoxes.local(end, frame);
        var oneWay = BedrockOneWayBoxes.afterTick(movement.previousState(), end, world);
        BedrockMovementState kept = state.withOneWayBoxes(oneWay);
        var passable = oneWay.stream()
                .map(passed -> BedrockOneWayBoxes.local(passed, frame))
                .toList();
        // Only blocks push: an actor the box overlaps is left through the move or not at all.
        var shapes = world.blocks().stream()
                .filter(block -> !block.isEntityCollision())
                .flatMap(block -> block.collisionBoxes().stream())
                .map(shape -> BedrockOneWayBoxes.local(shape, frame))
                .filter(shape -> !BedrockOneWayBoxes.inside(shape, passable))
                .toList();
        Vec3 direction = WorldStageBuilder.moveTowardsClosestSpaceBedrock(box, shapes);
        if (direction == null) return kept;
        float x = (float) direction.x;
        float z = (float) direction.z;
        float length = (float) Math.sqrt(x * x + z * z);
        if (length < MIN_DIRECTION_LENGTH) return kept;
        var velocity = kept.velocity();
        return kept.withVelocityAndCollisionFlags(
                new Vec3d(
                        pushed(velocity.x(), x / length, standingSlowdown),
                        velocity.y(),
                        pushed(velocity.z(), z / length, standingSlowdown)),
                kept.collisionFlags());
    }

    private static double pushed(double velocity, float direction, float standingSlowdown) {
        float push = direction * PUSH_SPEED * standingSlowdown;
        float limit = Math.abs(push);
        if (Math.abs((float) velocity) >= limit) return velocity;
        return Math.max(-limit, Math.min(limit, (float) velocity + push));
    }
}
