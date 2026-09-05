package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputIntent;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;

public final class BedrockAerialMovement {
    public static final double AIR_GRAVITY = 0.08D;

    private static final double SLOW_FALLING_AIR_GRAVITY = 0.01D;
    private static final double AIR_VERTICAL_DRAG = 0.98D;
    private static final double LEVITATION_ACCELERATION_PER_LEVEL = 0.01D;
    private static final double LEVITATION_VERTICAL_DRAG = 0.8D;
    private static final float GLIDE_GRAVITY = 0.08F;
    private static final float GLIDE_LIFT_SCALE = 0.1F;
    private static final float GLIDE_PITCH_HORIZONTAL_SCALE = 0.04F;
    private static final float GLIDE_PITCH_VERTICAL_SCALE = 3.2F;
    private static final float GLIDE_HORIZONTAL_ALIGNMENT = 0.1F;
    private static final float GLIDE_HORIZONTAL_DRAG = 0.99F;
    private static final float GLIDE_VERTICAL_DRAG = 0.98F;
    private static final float GLIDE_INPUT_WANT_UP_BOOST = 0.1F;
    private static final long MIN_GLIDE_TICKS_FOR_WANT_UP_BOOST = 10L;
    private static final double RIPTIDE_IMPULSE_PER_LEVEL_PLUS_ONE = 0.75D;
    private static final float GROUNDED_RIPTIDE_WATER_EXIT_MULTIPLIER = 0.98F / 0.8F;
    private static final float GROUNDED_RIPTIDE_VERTICAL_BOOST = 0.08F;

    private BedrockAerialMovement() {
    }

    public static double airGravity(Vec3d startingVelocity, boolean slowFalling) {
        return slowFalling && startingVelocity.y() < 0.0D ? SLOW_FALLING_AIR_GRAVITY : AIR_GRAVITY;
    }

    public static double airDraggedVelocity(double verticalVelocity, double gravity) {
        return (float) (((float) verticalVelocity - (float) gravity) * (float) AIR_VERTICAL_DRAG);
    }

    public static double airDraggedVelocityWithoutGravity(double verticalVelocity) {
        return (float) ((float) verticalVelocity * (float) AIR_VERTICAL_DRAG);
    }

    public static double levitationVelocityBeforeVerticalDrag(double currentVelocityY, int level) {
        return currentVelocityY * LEVITATION_VERTICAL_DRAG + LEVITATION_ACCELERATION_PER_LEVEL * level;
    }

    public static double levitationDraggedVelocity(double currentVelocityY, int level) {
        return levitationVelocityBeforeVerticalDrag(currentVelocityY, level) * AIR_VERTICAL_DRAG;
    }

    public static Vec3d glideVelocity(Vec3d currentVelocity, BedrockInputFrame frame) {
        float pitchRadians = frame.pitch() * BedrockMath.DEGREES_TO_RADIANS;
        float viewPitchRadians = frame.pitch() * -BedrockMath.DEGREES_TO_RADIANS;
        float viewYawRadians = frame.yaw() * -BedrockMath.DEGREES_TO_RADIANS - BedrockMath.PI;
        float negativeCosViewPitch = -BedrockMath.cos(viewPitchRadians);
        float viewX = BedrockMath.sin(viewYawRadians) * negativeCosViewPitch;
        float viewY = BedrockMath.sin(viewPitchRadians);
        float viewZ = BedrockMath.cos(viewYawRadians) * negativeCosViewPitch;
        float cosPitch = BedrockMath.cos(pitchRadians);
        float horizontalViewLength = (float) Math.sqrt(viewX * viewX + viewZ * viewZ);
        float horizontalVelocityLength = (float) Math.sqrt(
            (float) currentVelocity.x() * (float) currentVelocity.x()
                + (float) currentVelocity.z() * (float) currentVelocity.z()
        );
        float viewLength = (float) Math.sqrt(viewX * viewX + viewY * viewY + viewZ * viewZ);
        float glideFactor = Math.min(viewLength / 0.4F, 1.0F) * cosPitch * cosPitch;

        float velocityX = (float) currentVelocity.x();
        float velocityY = (float) currentVelocity.y() + (glideFactor * 0.75F - 1.0F) * GLIDE_GRAVITY;
        float velocityZ = (float) currentVelocity.z();
        if (horizontalViewLength > 0.0F && velocityY < 0.0F) {
            float lift = glideFactor * velocityY * -GLIDE_LIFT_SCALE;
            velocityX += viewX * lift / horizontalViewLength;
            velocityY += lift;
            velocityZ += viewZ * lift / horizontalViewLength;
        }
        if (pitchRadians < 0.0F && horizontalViewLength > 0.0F) {
            float pitchLift = BedrockMath.sin(pitchRadians) * horizontalVelocityLength * -GLIDE_PITCH_HORIZONTAL_SCALE;
            velocityX -= viewX * pitchLift / horizontalViewLength;
            velocityY += pitchLift * GLIDE_PITCH_VERTICAL_SCALE;
            velocityZ -= viewZ * pitchLift / horizontalViewLength;
        }
        if (horizontalViewLength > 0.0F) {
            velocityX += (horizontalVelocityLength * viewX / horizontalViewLength - velocityX) * GLIDE_HORIZONTAL_ALIGNMENT;
            velocityZ += (horizontalVelocityLength * viewZ / horizontalViewLength - velocityZ) * GLIDE_HORIZONTAL_ALIGNMENT;
        }
        return new Vec3d(
            velocityX * GLIDE_HORIZONTAL_DRAG,
            velocityY * GLIDE_VERTICAL_DRAG,
            velocityZ * GLIDE_HORIZONTAL_DRAG
        );
    }

    public static Vec3d glideInputSystemVelocity(
        Vec3d currentVelocity,
        BedrockMovementState current,
        BedrockInputIntent intent,
        BedrockMovementContext context,
        boolean actorGlidingAfterActions
    ) {
        if (!actorGlidingAfterActions
            || !context.movementAbilityMayFly()
            || current.fallFlyTicks() <= MIN_GLIDE_TICKS_FOR_WANT_UP_BOOST
            || !intent.vertical().wantUp()) {
            return currentVelocity;
        }
        return new Vec3d(
            currentVelocity.x(),
            (float) currentVelocity.y() + GLIDE_INPUT_WANT_UP_BOOST,
            currentVelocity.z()
        );
    }

    public static Vec3d riptideImpulse(
        BedrockInputFrame frame,
        int riptideLevel,
        boolean onGround,
        boolean wasInWater,
        boolean headInWater
    ) {
        Vec3d direction = BedrockMath.viewVector(frame);
        float directionX = (float) direction.x();
        float directionY = (float) direction.y();
        float directionZ = (float) direction.z();
        float length = (float) Math.sqrt(directionX * directionX + directionY * directionY + directionZ * directionZ);
        if (length == 0.0F) {
            return Vec3d.ZERO;
        }
        float scale = (float) riptideImpulseStrength(riptideLevel) / length;
        float impulseY = directionY * scale;
        if (onGround) {
            impulseY = wasInWater && !headInWater
                ? impulseY * GROUNDED_RIPTIDE_WATER_EXIT_MULTIPLIER
                : impulseY + GROUNDED_RIPTIDE_VERTICAL_BOOST;
        }
        return new Vec3d(
            directionX * scale,
            impulseY,
            directionZ * scale
        );
    }

    private static double riptideImpulseStrength(int riptideLevel) {
        return (riptideLevel + 1.0D) * RIPTIDE_IMPULSE_PER_LEVEL_PLUS_ONE;
    }
}
