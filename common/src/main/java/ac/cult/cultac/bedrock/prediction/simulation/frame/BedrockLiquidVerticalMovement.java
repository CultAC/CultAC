package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputIntent;

public final class BedrockLiquidVerticalMovement {
    public static final double WATER_GRAVITY = 0.005D;
    public static final double WATER_VERTICAL_IMPULSE = 0.03999999910593033D;
    public static final double LAVA_FRICTION = 0.5D;
    public static final double LAVA_GRAVITY = 0.02D;
    private BedrockLiquidVerticalMovement() {
    }

    public static boolean descendInput(BedrockInputIntent intent) {
        return intent.vertical().descendInput();
    }

    public static Vec3d waterDescendVelocity(Vec3d currentVelocity) {
        return new Vec3d(
            currentVelocity.x(),
            (double) (float) ((float) currentVelocity.y() - (float) WATER_VERTICAL_IMPULSE),
            currentVelocity.z()
        );
    }

    public static double waterJumpImpulse(boolean jumpImpulseApplies) {
        return jumpImpulseApplies ? WATER_VERTICAL_IMPULSE : 0.0D;
    }

    public static double lavaSwimUpVelocityY(double baseVelocityY) {
        return (float) ((float) baseVelocityY + (float) WATER_VERTICAL_IMPULSE);
    }

    public static double lavaDraggedVelocityY(double moveY) {
        return (float) ((float) moveY * (float) LAVA_FRICTION);
    }

    public static double waterNextTickVelocityY(double moveY, boolean gravityApplies) {
        double velocityY = moveY * BedrockWaterTravelMovement.FRICTION;
        return gravityApplies ? applyWaterGravity(velocityY) : velocityY;
    }

    public static double lavaNextTickVelocityY(double moveY, boolean gravityApplies) {
        float velocityY = (float) lavaDraggedVelocityY(moveY);
        if (gravityApplies) {
            velocityY = (float) applyLavaGravity(velocityY);
        }
        return velocityY;
    }

    public static double applyLavaGravity(double velocityY) {
        return (float) ((float) velocityY - (float) LAVA_GRAVITY);
    }

    public static double applyWaterGravity(double velocityY) {
        return velocityY - WATER_GRAVITY;
    }
}
