package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.VelocityTransformer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.math.Vec3;

/**
 * The movement input a tick's keys add. KeyboardInput#tick builds the move vector from the keys the
 * client sends in PLAYER_INPUT, LocalPlayer#modifyInput scales it, and LivingEntity#travel adds it through
 * Entity#moveRelative and Entity#getInputVector.
 */
public final class ClientKeyInput {
    private ClientKeyInput() {}

    /**
     * LivingEntity#moveRelative's addition for {@code keys}: KeyboardInput#tick, LocalPlayer#modifyInput with
     * {@code itemUseMultiplier} and {@code movingSlowlyFactor}, and Entity#getInputVector at {@code speed}.
     * LocalPlayer#aiStep computes crouching from the previous keys before Input#tick; {@code movingSlowlyFactor} carries
     * its outcome.
     */
    public static Vec3 travelInput(
            CultPlayer player,
            SimulationContext context,
            KnownInput keys,
            float itemUseMultiplier,
            float movingSlowlyFactor,
            float speed) {
        // LivingEntity#aiStep clears xxa/zza after applyInput when health <= 0. PLAYER_INPUT still reports the
        // held keys. Use receipt-proven health, not the legacy isDead flag which may precede the health packet.
        if (!player.compensatedEntities.getSelf().actionAlive) return Vec3.ZERO;
        Vec3 modified = VelocityTransformer.getExactControllerModifiedInputStatic(
                new Vec3(keys.strafeImpulse(), 0.0D, keys.forwardImpulse()), itemUseMultiplier, movingSlowlyFactor);
        return inputVector(context, modified, speed);
    }

    /**
     * {@link #travelInput} for a player Entity#updateSwimming left swimming in water, where no pose slows the input:
     * LocalPlayer#aiStep never crouches a swimming player, and Entity#isVisuallyCrawling is false in water.
     */
    public static Vec3 swimmingTravelInput(
            CultPlayer player, SimulationContext context, KnownInput keys, float itemUseMultiplier, float speed) {
        if (!player.compensatedEntities.getSelf().actionAlive) return Vec3.ZERO;
        Vec3 modified = VelocityTransformer.getExactControllerModifiedInputStatic(
                new Vec3(keys.strafeImpulse(), 0.0D, keys.forwardImpulse()), itemUseMultiplier, 1.0F);
        return inputVector(context, modified, speed);
    }

    /** {@link #travelInput}'s x and z written into {@code out[at]} and {@code out[at + 1]}. */
    public static void travelInput(
            double[] out,
            int at,
            CultPlayer player,
            SimulationContext context,
            KnownInput keys,
            float itemUseMultiplier,
            float movingSlowlyFactor,
            float speed) {
        if (!player.compensatedEntities.getSelf().actionAlive) {
            out[at] = Vec3.ZERO.x;
            out[at + 1] = Vec3.ZERO.z;
            return;
        }
        Vec3 modified = VelocityTransformer.getExactControllerModifiedInputStatic(
                new Vec3(keys.strafeImpulse(), 0.0D, keys.forwardImpulse()), itemUseMultiplier, movingSlowlyFactor);
        inputVector(out, at, context, modified, speed);
    }

    /** {@link #inputVector(SimulationContext, Vec3, float)}'s x and z written into {@code out}. */
    private static void inputVector(double[] out, int at, SimulationContext context, Vec3 input, float speed) {
        double lengthSqr = input.lengthSqr();
        if (lengthSqr < 1.0E-7D) {
            out[at] = Vec3.ZERO.x;
            out[at + 1] = Vec3.ZERO.z;
            return;
        }
        double x = input.x;
        double z = input.z;
        if (lengthSqr > 1.0D) {
            // Vec3#normalize.
            double length = input.length();
            x = length < 1.0E-5F ? Vec3.ZERO.x : x / length;
            z = length < 1.0E-5F ? Vec3.ZERO.z : z / length;
        }
        double movementX = x * speed;
        double movementZ = z * speed;
        float yawRadians = context.getXRot() * ((float) Math.PI / 180F);
        float sin = context.getTrig().sin(yawRadians);
        float cos = context.getTrig().cos(yawRadians);
        out[at] = movementX * cos - movementZ * sin;
        out[at + 1] = movementZ * cos + movementX * sin;
    }

    /** Entity#getInputVector. */
    private static Vec3 inputVector(SimulationContext context, Vec3 input, float speed) {
        double lengthSqr = input.lengthSqr();
        if (lengthSqr < 1.0E-7D) return Vec3.ZERO;
        Vec3 movement = (lengthSqr > 1.0D ? input.normalize() : input).scale(speed);
        float yawRadians = context.getXRot() * ((float) Math.PI / 180F);
        float sin = context.getTrig().sin(yawRadians);
        float cos = context.getTrig().cos(yawRadians);
        return new Vec3(movement.x * cos - movement.z * sin, movement.y, movement.z * cos + movement.x * sin);
    }
}
