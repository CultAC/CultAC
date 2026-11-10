package ac.cult.cultac.bedrock.prediction.simulation.travel;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputIntent;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMath;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;

public final class BedrockFlyingTravelMovement {
    private static final double FLY_ASCEND_ACCELERATION = 0.15F;
    private static final double FLY_DESCEND_ACCELERATION = 0.22F;
    private static final double SPRINT_FLY_SPEED_SCALE = 2.0D;
    private static final double PLAYER_FLYING_VERTICAL_DRAG = 0.6D;

    private BedrockFlyingTravelMovement() {}

    /**
     * Flight accelerates by the fly speed, and by twice that while the actor sprints. The move
     * is followed by the friction of the block stood on at the start of the tick, or of air.
     */
    public static BedrockTravelHorizontalControl.Step speed(
            BedrockMovementContext context, float moveInputScale, boolean sprinting, double friction) {
        float flySpeed = BedrockHorizontalSpeedControl.flyingSpeed(
                context.movementAbilityFlySpeed(), sprinting ? SPRINT_FLY_SPEED_SCALE : 1.0D);
        return new BedrockTravelHorizontalControl.Step(flySpeed * moveInputScale, friction);
    }

    /**
     * Up input adds 0.15 to the carried vertical velocity and down input takes 0.22 from it,
     * whatever the fly speed. Both together stop the vertical velocity, and neither leaves it to
     * the drag alone.
     */
    public static Vec3d applyVerticalInput(
            Vec3d velocity, BedrockInputFrame frame, BedrockInputIntent intent, boolean descendInput) {
        boolean ascendInput = intent.vertical().upwardInput(frame.jumping());
        if (ascendInput && descendInput) {
            return new Vec3d(velocity.x(), 0.0D, velocity.z());
        }
        if (!ascendInput && !descendInput) {
            return velocity;
        }
        double acceleration = ascendInput ? FLY_ASCEND_ACCELERATION : -FLY_DESCEND_ACCELERATION;
        return new Vec3d(velocity.x(), BedrockMath.f(velocity.y() + acceleration), velocity.z());
    }

    public static Vec3d applyVerticalDrag(Vec3d velocity) {
        return new Vec3d(velocity.x(), velocity.y() * PLAYER_FLYING_VERTICAL_DRAG, velocity.z());
    }
}
