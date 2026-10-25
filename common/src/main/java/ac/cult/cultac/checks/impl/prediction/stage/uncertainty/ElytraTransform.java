package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.Vector3dm;
import ac.cult.cultac.utils.math.VectorUtils;
import ac.cult.cultac.utils.nmsutil.ReachUtils;

public class ElytraTransform implements UncertaintyHandler {
    /** Holds for a start trace, whose pre-collision velocity is its position: the pipeline runs this first. */
    @Override
    public boolean skipsContext(CultPlayer player, SimulationContext context, PredictionResult lastResult) {
        return !context.usesFallFlyingMovement() || context.getWorldData().mustBeInLiquid();
    }

    @Override
    public MovementTrace handleMovementTrace(
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastResult,
            MovementTrace trace,
            Vec3 end) {
        PredVector velocity = handleUncertainty(player, valid, result, context, lastResult, trace.position(), end);
        // LivingEntity#travelFallFlying stores this velocity before Entity#move.
        // Collision clipping changes displacement; restitution reads the stored velocity.
        return trace.withPosition(velocity).withPreCollisionVelocity(velocity);
    }

    @Override
    public PredVector handleUncertainty(
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastResult,
            PredVector start,
            Vec3 end) {
        if (!result.getSimulationContext().usesFallFlyingMovement()) return start;
        if (result.getSimulationContext().getWorldData().mustBeInLiquid()) return start;

        SimpleCollisionBox startingHorizontalUncertainty = valid.getCollisionIgnoredMaxStartingVelExtents();
        double uncertainty = startingHorizontalUncertainty == null
                ? 0
                : Math.hypot(
                        startingHorizontalUncertainty.maxX - startingHorizontalUncertainty.minX,
                        startingHorizontalUncertainty.maxZ - startingHorizontalUncertainty.minZ);
        double yUncertainty = uncertainty * 0.04 * 3.2; // Exact values, we could also look at Y

        // LivingEntity#aiStep applies bubble callbacks after travel. Their existing
        // Y range is therefore input to the next tick's elytra transform, including X/Z.
        PredVector minStart = start, maxStart = start;
        // A server velocity set replaces the stored velocity; a grounded riptide lift that consumes a stuck
        // multiplier zeroes it.
        boolean replaced = start.hasReason("Velocity") || context.getRiptideLiftStuck() != null;
        if (BubbleColumn.beforeElytra(context) && !replaced) {
            Boosted boosted = start.firstInLineage(Boosted.class);
            if (boosted == null) {
                minStart = BubbleColumn.apply(context, lastResult, start, Double.NEGATIVE_INFINITY);
                maxStart = BubbleColumn.apply(context, lastResult, start, Double.POSITIVE_INFINITY);
            } else {
                // The bubble callbacks precede the rocket boosts, each of which halves a change in y.
                double scale = Math.pow(0.5, boosted.boosts);
                PredVector carried = boosted.carried;
                double down = BubbleColumn.apply(context, lastResult, carried, Double.NEGATIVE_INFINITY).y - carried.y;
                double up = BubbleColumn.apply(context, lastResult, carried, Double.POSITIVE_INFINITY).y - carried.y;
                minStart = start.withXYZOrSelf(start.x, start.y + down * scale, start.z, "bubble column");
                maxStart = start.withXYZOrSelf(start.x, start.y + up * scale, start.z, "bubble column");
            }
        }
        SimpleCollisionBox allowed = null;

        // Don't require the player to have elytra if they MIGHT be in water
        if (context.getWorldData().maybeInLiquid()) {
            allowed = new SimpleCollisionBox(minStart, maxStart);
        }

        for (int loopFastmath = 0; loopFastmath < 2; loopFastmath++) {
            // LivingEntity#updateFallFlyingMovement reads the gravity from the velocity it starts from.
            allowed = glide(player, result, context, allowed, minStart);
            if (minStart.y != maxStart.y) {
                allowed = glide(player, result, context, allowed, maxStart);
                if (Math.min(minStart.y, maxStart.y) <= 0 && Math.max(minStart.y, maxStart.y) > 0) {
                    // LivingEntity#getEffectiveGravity switches at a y of zero; on either side the glide is
                    // monotonic in y, so each side's ends bound it.
                    allowed = glide(player, result, context, allowed, new Vec3(minStart.x, 0, minStart.z));
                    allowed = glide(
                            player, result, context, allowed, new Vec3(minStart.x, Double.MIN_VALUE, minStart.z));
                }
            }
            player.trigHandler.toggleShitMath();
        }

        assert allowed != null;
        allowed.expand(0, yUncertainty, 0);

        Vec3 cut = VectorUtils.cutBoxToVector(end, allowed);
        return start.withXYZ(cut.x, cut.y, cut.z, "elytra");
    }

    /**
     * A stored velocity FireworkRocketEntity#tick boosted {@code boosts} times after the last tick's travel, and the
     * velocity it boosted.
     */
    public static final class Boosted extends PredVector {
        final PredVector carried;
        final int boosts;

        public Boosted(PredVector carried, Vec3 boosted, int boosts) {
            super(boosted, carried, "firework");
            this.carried = carried;
            this.boosts = boosts;
        }
    }

    private SimpleCollisionBox glide(
            CultPlayer player,
            PredictionResult result,
            SimulationContext context,
            SimpleCollisionBox allowed,
            Vec3 velocity) {
        for (double gravity : result.getPossibleGravity(velocity.y, velocity.y)) {
            allowed = union(allowed, transformElytra(player, context, velocity, gravity));
        }
        return allowed;
    }

    private static SimpleCollisionBox union(SimpleCollisionBox allowed, Vec3 movement) {
        SimpleCollisionBox box = new SimpleCollisionBox(movement, movement);
        return allowed == null ? box : allowed.union(box);
    }

    public static Vec3 boostFirework(Vec3 velocity, Vector3dm look) {
        return velocity.add(
                look.getX() * 0.1 + (look.getX() * 1.5 - velocity.x) * 0.5,
                look.getY() * 0.1 + (look.getY() * 1.5 - velocity.y) * 0.5,
                look.getZ() * 0.1 + (look.getZ() * 1.5 - velocity.z) * 0.5);
    }

    public Vec3 transformElytra(CultPlayer player, SimulationContext context, Vec3 start, double gravity) {
        // MCP-Reborn LivingEntity#updateFallFlyingMovement reads getLookAngle()
        // and getXRot() from the entity state being ticked. In Cult that state
        // is the packet-local SimulationContext, not mutable CultPlayer fields
        // that may already have advanced while candidate vectors are replayed.
        Vector3dm currentLook = ReachUtils.getLook(player, context.getXRot(), context.getYRot());
        return getElytraMovement(player, start, currentLook, context.getYRot(), gravity);
    }

    public static Vec3 getElytraMovement(
            CultPlayer player, Vec3 vector, Vector3dm lookVector, float pitch, double gravity) {
        float yRotRadians = pitch * 0.017453292F;
        double horizontalLookLength =
                Math.sqrt(lookVector.getX() * lookVector.getX() + lookVector.getZ() * lookVector.getZ());
        double velocityHorizLength = new Vec3(vector.x, 0, vector.z).length();
        double length = lookVector.length();

        double vertCosRotation = Math.cos(yRotRadians);
        // Modern clients retain this intermediate as a double. Casting it back
        // to float here produces measurable 1.20.2+ glide drift.
        vertCosRotation = vertCosRotation * vertCosRotation * Math.min(1.0D, length / 0.4D);

        vector = vector.add(new Vec3(0.0D, gravity * (-1.0D + vertCosRotation * 0.75D), 0.0D));

        // Handle slowing the player down when falling
        if (vector.y < 0.0D && horizontalLookLength > 0.0D) {
            double d5 = vector.y * -0.1D * vertCosRotation;
            vector = vector.add(new Vec3(
                    lookVector.getX() * d5 / horizontalLookLength, d5, lookVector.getZ() * d5 / horizontalLookLength));
        }

        // Handle accelerating the player when they are looking down
        if (yRotRadians < 0.0F && horizontalLookLength > 0.0D) {
            double d5 = velocityHorizLength * (double) (-player.trigHandler.sin(yRotRadians)) * 0.04D;
            vector = vector.add(new Vec3(
                    -lookVector.getX() * d5 / horizontalLookLength,
                    d5 * 3.2D,
                    -lookVector.getZ() * d5 / horizontalLookLength));
        }

        // Handle accelerating the player sideways
        if (horizontalLookLength > 0) {
            vector = vector.add(new Vec3(
                    (lookVector.getX() / horizontalLookLength * velocityHorizLength - vector.x) * 0.1D,
                    0.0D,
                    (lookVector.getZ() / horizontalLookLength * velocityHorizLength - vector.z) * 0.1D));
        }

        return vector.multiply(0.99F, 0.98F, 0.99F);
    }
}
