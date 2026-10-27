package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementMath;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.Vector3dm;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import java.util.ArrayList;
import java.util.List;

public class ElytraTransform implements UncertaintyHandler {
    /** Holds for a start trace, whose pre-collision velocity is its position: the pipeline runs this first. */
    @Override
    public boolean skipsContext(CultPlayer player, SimulationContext context, PredictionResult lastResult) {
        return !context.usesFallFlyingMovement() || context.getWorldData().isInLiquid();
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
        if (!context.usesFallFlyingMovement() || context.getWorldData().isInLiquid()) return start;
        Candidate candidate = start.firstInLineage(Candidate.class);
        double gravity =
                candidate == null ? result.getPossibleGravity(start.y, start.y).getFirst() : candidate.gravity;
        Vec3 movement =
                JavaMovementMath.calculate(player, start, () -> transformElytra(player, context, start, gravity));
        return start.with(movement, "elytra");
    }

    /** Each concrete gravity choice gets its own forward collision result. */
    public static List<PredVector> candidates(SimulationContext context, List<PredVector> velocities) {
        if (!context.usesFallFlyingMovement() || context.getWorldData().isInLiquid()) return velocities;
        var actor = context.getEntities().getEntityInControl();
        double normal = actor.hasGravity ? actor.gravity : 0.0D;
        boolean slow = actor.hasGravity && context.getEntities().getSlowFallingAmplifier() != null;
        var candidates = new ArrayList<PredVector>(velocities.size() * 2);
        for (PredVector velocity : velocities) {
            double gravity = slow && velocity.y <= 0.0D ? Math.min(normal, 0.01D) : normal;
            candidates.add(new Candidate(velocity, gravity));
        }
        return candidates;
    }

    private static final class Candidate extends PredVector {
        private final double gravity;

        private Candidate(PredVector velocity, double gravity) {
            super(velocity, velocity, "glide gravity " + gravity);
            this.gravity = gravity;
        }
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
            double d5 = velocityHorizLength * (double) (-player.trigHandler.clientSin(yRotRadians)) * 0.04D;
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
