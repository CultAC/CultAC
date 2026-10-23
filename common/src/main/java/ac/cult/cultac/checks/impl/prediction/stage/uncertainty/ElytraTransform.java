package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.VelocityTransformer;
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

        // LivingEntity#aiStep's bubble callbacks after travel are already in the carried velocity.
        PredVector minStart = start, maxStart = start;
        // A server velocity set replaces the stored velocity; a grounded riptide lift that consumes a stuck
        // multiplier zeroes it.
        boolean replaced = start.hasReason("Velocity") || context.getRiptideLiftStuck() != null;
        // FireworkRocketEntity#tick runs after the LocalPlayer's tick and boosts the stored velocity once per
        // attached rocket with that tick's look; a server velocity set is handled after it and replaces it.
        int fireworks = replaced ? 0 : player.compensatedFireworks.getMaxFireworksAppliedPossible();
        // That boost precedes this tick's TridentItem#releaseUsing push and LivingEntity#aiStep's 0.003 cut.
        Riptide.Pushed pushed = fireworks == 0 ? null : Riptide.pushOf(context, start);
        PredVector minCarried = minStart, maxCarried = maxStart;
        if (pushed != null) {
            minCarried = maxCarried = pushed.carried;
        }
        Vec3[] boostedMin = {minCarried, minCarried};
        Vec3[] boostedMax = {maxCarried, maxCarried};
        Vec3 cut = null;
        double closest = Double.POSITIVE_INFINITY;
        for (int boosts = 0; boosts <= fireworks; boosts++) {
            SimpleCollisionBox allowed = null;

            // Don't require the player to have elytra if they MIGHT be in water
            if (boosts == 0 && context.getWorldData().maybeInLiquid()) {
                allowed = new SimpleCollisionBox(minStart, maxStart);
            }

            for (int loopFastmath = 0; loopFastmath < 2; loopFastmath++) {
                if (boosts > 0) {
                    Vector3dm look = ReachUtils.getLook(player, context.getLastXRot(), context.getLastYRot());
                    boostedMin[loopFastmath] = boostFirework(boostedMin[loopFastmath], look);
                    boostedMax[loopFastmath] = minCarried == maxCarried
                            ? boostedMin[loopFastmath]
                            : boostFirework(boostedMax[loopFastmath], look);
                }
                Vec3 min = boosts == 0 ? minStart : launch(context, pushed, boostedMin[loopFastmath]);
                Vec3 max = boosts == 0 ? maxStart : launch(context, pushed, boostedMax[loopFastmath]);
                // LivingEntity#updateFallFlyingMovement reads the gravity from the velocity it starts from.
                allowed = glide(player, result, context, allowed, min);
                if (min.y != max.y) {
                    allowed = glide(player, result, context, allowed, max);
                    if (Math.min(min.y, max.y) <= 0 && Math.max(min.y, max.y) > 0) {
                        // LivingEntity#getEffectiveGravity switches at a y of zero; on either side the glide is
                        // monotonic in y, so each side's ends bound it.
                        allowed = glide(player, result, context, allowed, new Vec3(min.x, 0, min.z));
                        allowed = glide(player, result, context, allowed, new Vec3(min.x, Double.MIN_VALUE, min.z));
                    }
                }
                player.trigHandler.toggleShitMath();
            }

            assert allowed != null;
            allowed.expand(0, yUncertainty, 0);

            Vec3 candidate = VectorUtils.cutBoxToVector(end, allowed);
            double distance = candidate.distanceToSqr(end);
            if (distance < closest) {
                closest = distance;
                cut = candidate;
            }
        }

        return start.withXYZ(cut.x, cut.y, cut.z, "elytra");
    }

    /** TridentItem#releaseUsing's push on a boosted velocity, then LivingEntity#aiStep's 0.003 cut. */
    private static Vec3 launch(SimulationContext context, Riptide.Pushed pushed, Vec3 boosted) {
        if (pushed == null) return boosted;
        return VelocityTransformer.applyMovementThreshold(
                new PredVector(boosted.add(pushed.push)), context.getVersion(), true);
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

    private static Vec3 boostFirework(Vec3 velocity, Vector3dm look) {
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
