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
    private static final float MOVE_INPUT_DEADZONE = 0.01F;
    private static final double CREATIVE_BRAKE = 0.375F;
    private static final double BRAKE = 0.75F;

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
     * whatever the fly speed. Both together stop the vertical velocity. Without either, a creative
     * player with no horizontal input brakes the carried vertical velocity to 0.375 before the
     * move; otherwise the velocity is left to the drag.
     */
    public static Vec3d applyVerticalInput(
            Vec3d velocity,
            BedrockInputFrame frame,
            BedrockInputIntent intent,
            boolean descendInput,
            Vec3d control,
            boolean creative) {
        boolean ascendInput = intent.vertical().upwardInput(frame.jumping());
        if (ascendInput && descendInput) {
            return new Vec3d(velocity.x(), 0.0D, velocity.z());
        }
        if (!ascendInput && !descendInput) {
            return creative && !horizontalInput(control)
                    ? new Vec3d(velocity.x(), BedrockMath.f(velocity.y() * CREATIVE_BRAKE), velocity.z())
                    : velocity;
        }
        double acceleration = ascendInput ? FLY_ASCEND_ACCELERATION : -FLY_DESCEND_ACCELERATION;
        return new Vec3d(velocity.x(), BedrockMath.f(velocity.y() + acceleration), velocity.z());
    }

    /**
     * A tick of flight input without horizontal input brakes the horizontal velocity the friction
     * leaves: to 0.375 for a creative player and to 0.75 in the other game modes.
     */
    public static BedrockTravelHorizontalControl.Step brakeHorizontal(
            BedrockTravelHorizontalControl.Step step, Vec3d control, boolean creative) {
        if (horizontalInput(control)) {
            return step;
        }
        double brake = creative ? CREATIVE_BRAKE : BRAKE;
        return new BedrockTravelHorizontalControl.Step(step.horizontalInputLimit(), step.horizontalFriction() * brake);
    }

    private static boolean horizontalInput(Vec3d control) {
        return control != null
                && (Math.abs((float) control.x()) >= MOVE_INPUT_DEADZONE
                        || Math.abs((float) control.z()) >= MOVE_INPUT_DEADZONE);
    }

    public static Vec3d applyVerticalDrag(Vec3d velocity) {
        return new Vec3d(velocity.x(), velocity.y() * PLAYER_FLYING_VERTICAL_DRAG, velocity.z());
    }
}
