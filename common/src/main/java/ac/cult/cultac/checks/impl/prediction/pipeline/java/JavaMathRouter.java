package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.utils.math.OptifineFastMath;
import ac.cult.cultac.utils.math.VanillaMath;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;

/** Invert moveRelative to order exact inputs; discard only movements no Entity#collide can produce. */
public final class JavaMathRouter {
    private JavaMathRouter() {}

    /** Ordinary exact travel stays on the cached profile, without even reading the other trig table. */
    public static boolean needsAlternate(PredictionResult result) {
        var context = result.getSimulationContext();
        var valid = result.getValidMovements();
        var trace = valid.getClosestTrace();
        var collide = result.getCollideAxisData();
        var world = context.getWorldData();
        boolean horizontal = collide.getX().isLikelyCollide() || collide.getZ().isLikelyCollide();
        boolean vertical = collide.getYNeg() != null && collide.getYNeg().isLikelyCollide()
                || collide.getYPos() != null && collide.getYPos().isLikelyCollide();
        Vec3 input = result.getInitialStartingVel().getTravelInput();
        var use = result.getItemUseResult();
        boolean hidden = result.getInitialStartingVel().getVelocityRange() != null
                || trace.passAfterTravel() != null
                || trace.positionOnlyDelta().lengthSqr() != 0
                || valid.getDebugAllowance().lengthSqr() != 0
                || context.isWithheldPosition() && (context.isGliding() || input != null && input.lengthSqr() != 0)
                || context.isSneaking()
                        && (trace.preCollisionMovement().x
                                        != trace.preCollisionVelocity().x * context.getLastStuckSpeed().x
                                || trace.preCollisionMovement().z
                                        != trace.preCollisionVelocity().z * context.getLastStuckSpeed().z)
                || horizontal
                        && (context.getVehicle() != null
                                || context.isGliding()
                                || world.isInLiquid()
                                || world.getClimbingAtStart() != DesyncStatus.FALSE)
                || vertical && (context.isGliding() || world.isSwimming())
                || use != null && use.state().required() && use.slowedOffset() > reportRounding(context);
        boolean mismatch = !result.getFlags().isEmpty() || result.selectionOffset() > reportRounding(context);
        return (mismatch || hidden) && !samePlayerTables(context);
    }

    /** A player tick whose input, jump and look queries have bit-identical results needs only one profile. */
    public static boolean samePlayerTables(SimulationContext context) {
        if (context.getVehicle() != null || context.getRiptideLevel() != 0) return false;
        float yaw = context.getXRot() * ((float) Math.PI / 180F);
        float pitch = context.getYRot() * ((float) Math.PI / 180F);
        return same(context, yaw) && same(context, -yaw) && same(context, pitch);
    }

    private static boolean same(SimulationContext context, float angle) {
        return Float.floatToRawIntBits(VanillaMath.sin(context.getVersion(), angle))
                        == Float.floatToRawIntBits(OptifineFastMath.sin(angle))
                && Float.floatToRawIntBits(VanillaMath.cos(context.getVersion(), angle))
                        == Float.floatToRawIntBits(OptifineFastMath.cos(angle));
    }

    /** The scaled, normalized input before Entity#getInputVector's rotation. Raw keyboard keys are not recoverable. */
    public static Vec3 inverse(double x, double z, float sin, float cos) {
        double scale = (double) sin * sin + (double) cos * cos;
        if (scale == 0.0D) return null;
        return new Vec3((x * cos + z * sin) / scale, 0.0D, (z * cos - x * sin) / scale);
    }

    /**
     * A complete pre-input candidate already includes the table's jump and other impulses. Solve its required
     * acceleration and compare it with its legal processed input, rather than merely scoring axis/diagonal proximity.
     * This orders candidates only; the forward runner still validates every retained movement.
     */
    public static double inputError(SimulationContext context, PredVector candidate) {
        var math = candidate.firstInLineage(JavaMovementMath.class);
        Vec3 input = candidate.getTravelInput();
        if (math == null || input == null) return candidate.distanceToSqr(context.getTarget());
        float radians = context.getXRot() * ((float) Math.PI / 180F);
        float sin = math.vanilla() ? VanillaMath.sin(context.getVersion(), radians) : OptifineFastMath.sin(radians);
        float cos = math.vanilla() ? VanillaMath.cos(context.getVersion(), radians) : OptifineFastMath.cos(radians);
        Vec3 target = context.getTarget();
        double scale = (double) sin * sin + (double) cos * cos;
        if (scale == 0.0D) return Double.POSITIVE_INFINITY;
        double x = target.x - (candidate.x - input.x);
        double z = target.z - (candidate.z - input.z);
        double inputX = (x * cos + z * sin) / scale - (input.x * cos + input.z * sin) / scale;
        double inputZ = (z * cos - x * sin) / scale - (input.z * cos - input.x * sin) / scale;
        return inputX * inputX + inputZ * inputZ;
    }

    /**
     * Only the dry, exact-effects player path has no later horizontal velocity transform. A collision, step or
     * backoff can shorten each horizontal component towards zero, never extend it. Once one legal input reproduces
     * the horizontal report, siblings outside that necessary bound cannot be its math profile, even beside a wall.
     * Fluids, ladders, external shoves, withheld reports and velocity ranges keep all forward candidates.
     */
    public static List<PredVector> route(SimulationContext context, List<PredVector> candidates) {
        if (candidates.size() < 2 || !linearHorizontal(context)) return candidates;
        PredVector best = null;
        double error = Double.POSITIVE_INFINITY;
        for (PredVector candidate : candidates) {
            if (candidate.getVelocityRange() != null || !candidate.hasTravelInput()) return candidates;
            double next = inputError(context, candidate);
            if (next < error) {
                error = next;
                best = candidate;
            }
        }
        if (best == null) return candidates;
        Vec3 target = context.getTarget();
        double epsilonX = rounding(context.getStart().x, context.getEnd().x, target.x);
        double epsilonZ = rounding(context.getStart().z, context.getEnd().z, target.z);
        if (Math.abs(best.x - target.x) > epsilonX || Math.abs(best.z - target.z) > epsilonZ) return candidates;
        List<PredVector> kept = null;
        for (int i = 0; i < candidates.size(); i++) {
            PredVector candidate = candidates.get(i);
            boolean impossible = outside(target.x, candidate.x, epsilonX) || outside(target.z, candidate.z, epsilonZ);
            if (impossible && sameProofs(candidate, best)) {
                if (kept == null) kept = new ArrayList<>(candidates.subList(0, i));
            } else if (kept != null) kept.add(candidate);
        }
        return kept == null ? candidates : kept;
    }

    private static boolean linearHorizontal(SimulationContext context) {
        var world = context.getWorldData();
        return context.getVehicle() == null
                && !context.isGliding()
                && !context.isWithheldPosition()
                && context.getRiptideLevel() == 0
                && context.getPassAfterTravel() == null
                && JavaMovementEngine.contextUsesExactEffects(context)
                && !world.isInLiquid()
                && world.getClimbingAtStart() == DesyncStatus.FALSE
                && world.getNumPushing() == 0
                && world.getLoadingLevelPushes() == null;
    }

    private static boolean sameProofs(PredVector candidate, PredVector best) {
        return candidate.hasSameStartAs(best)
                && candidate.isJump() == best.isJump()
                && candidate.getAttackSlows() == best.getAttackSlows()
                && candidate.getSprintOutcome() == best.getSprintOutcome();
    }

    private static boolean outside(double report, double attempted, double epsilon) {
        return report < Math.min(0.0D, attempted) - epsilon || report > Math.max(0.0D, attempted) + epsilon;
    }

    /** Two profiles can round to the same absolute packet position while retaining distinct deltaMovement. */
    public static double reportRounding(SimulationContext context) {
        Vec3 start = context.getStart(), end = context.getEnd(), target = context.getTarget();
        Vec3 scale = context.getLastStuckSpeed();
        double x = rounding(start.x, end.x, target.x) / scale.x;
        double y = rounding(start.y, end.y, target.y) / scale.y;
        double z = rounding(start.z, end.z, target.z) / scale.z;
        return Math.sqrt(x * x + y * y + z * z);
    }

    // The report subtracts two absolute positions. This is their floating-point rounding, not a movement allowance.
    private static double rounding(double start, double end, double delta) {
        return 2.0D * (Math.ulp(start) + Math.ulp(end) + Math.ulp(delta));
    }
}
