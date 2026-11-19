package ac.cult.cultac.checks.impl.prediction;

import ac.cult.cultac.checks.impl.prediction.checks.HorizontalAnalyzer;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMoveCollision;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.Ladder;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.math.VanillaMath;
import ac.cult.cultac.utils.math.Vec3;

import java.util.List;

/**
 * The client's {@code horizontalCollision && !minorHorizontalCollision} after an accepted move, which
 * LocalPlayer#shouldStopRunSprinting reads in the next aiStep.
 *
 * <p>Entity#move sets horizontalCollision when the collided movement differs from the attempted delta by
 * {@code Mth.equal}'s 1e-5 on X or Z, and minorHorizontalCollision from LocalPlayer#isHorizontalCollisionMinor on the
 * collided movement: the angle between it and the xxa/zza input, rotated by yaw, is below 8 degrees. No input, or a
 * collided movement under 1e-5 squared, is never minor. The collided movement is the reported delta, so only the
 * attempted delta is unknown. KeyboardInput's move vector only points in the eight key directions, which
 * LocalPlayer#modifyInput scales without turning, so each direction is tested with any input length up to the
 * accepted input allowance. LocalPlayer#sendChanges reports in PLAYER_INPUT the keys KeyboardInput#tick read for the
 * tick's travel before it sends the move, and their direction alone decides whether the collision is minor. The
 * input direction takes the yaw in float radians through Mth's sine table, as isHorizontalCollisionMinor and
 * LivingEntity#moveRelative do. On a climbable, LivingEntity#handleRelativeFrictionAndCalculateMovement adds the input
 * before LivingEntity#handleOnClimbable clamps X and Z to 0.15, so an axis the clamp holds stays there for every input
 * that pushes it further.
 */
public final class ClientHorizontalCollision {
    private static final double TOLERANCE = HorizontalAnalyzer.HORIZONTAL_INPUT_UNCERTAINTY;
    private static final double MINOR_ANGLE = 0.13962634F;
    private static final double CLIMBABLE_LIMIT = 0.15F;
    private static final int SOFT = 1;
    private static final int HARD = 2;

    private ClientHorizontalCollision() {}

    /**
     * The verdict over every candidate that explains the report: the engine picks one of them as the move, but the
     * client may have run any. TRUE only when all end in a hard collision, FALSE only when none does, else UNKNOWN.
     */
    public static DesyncStatus hardForEveryReality(PredictionResult result) {
        DesyncStatus verdict = hardFrom(result);
        if (verdict == DesyncStatus.UNKNOWN || result.getRealities() == null) return verdict;
        for (PredictionResult other : result.getRealities()) {
            if (other == result || other.getFlagSeverity() != 0.0) continue;
            if (hardFrom(other) != verdict) return DesyncStatus.UNKNOWN;
        }
        return verdict;
    }

    /**
     * TRUE only when every key input consistent with the accepted move ends in a hard collision, from the starting
     * velocity of the selected reality and of every other judgeable zero-flag reality, any of which the client may
     * have moved from.
     */
    public static DesyncStatus hard(PredictionResult result) {
        DesyncStatus verdict = hardFrom(result);
        List<PredictionResult> realities = result.getRealities();
        for (int i = 0, size = realities == null ? 0 : realities.size(); i < size; i++) {
            if (verdict == DesyncStatus.FALSE) return verdict;
            PredictionResult reality = realities.get(i);
            if (reality == result || reality.getFlagSeverity() != 0.0 || !judgeable(reality)) continue;
            DesyncStatus other = hardFrom(reality);
            if (other != verdict) verdict = other == DesyncStatus.FALSE ? other : DesyncStatus.UNKNOWN;
        }
        return verdict;
    }

    private static boolean judgeable(PredictionResult result) {
        SimulationContext context = result.getSimulationContext();
        return context != null
                && context.getVehicle() == null
                && result.getValidMovements() != null
                && result.getCollideAxisData() != null
                && result.getValidMovements().getClosestTrace() != null;
    }

    private static DesyncStatus hardFrom(PredictionResult result) {
        if (!judgeable(result)) return DesyncStatus.UNKNOWN;
        SimulationContext context = result.getSimulationContext();
        MovementTrace trace = result.getValidMovements().getClosestTrace();
        // From 26.3 the block-entity pass runs after the travel. Its last Entity#move sets both flags again from its
        // own movement alone, so aiStep reads that move's collision whatever the travel's was.
        var pass = trace.passAfterTravel();
        if (pass != null && pass.result().lastMove() != null)
            return afterMove(
                    result,
                    pass.result().lastMove().delta(),
                    pass.result().lastMove().movement());
        if (result.getCollideAxisData() instanceof JavaMoveCollision move) return forwardHard(result, move);

        Vec3 target = result.getTarget();
        CollideAxisData collide = result.getCollideAxisData();
        int wallX = wallSide(collide.getX(), target.x);
        int wallZ = wallSide(collide.getZ(), target.z);
        if (wallX == 0 && wallZ == 0) return DesyncStatus.FALSE;

        float yaw = context.getXRot() * (float) (Math.PI / 180.0);
        CultPlayer player = result.getPlayer();
        Move move = Move.attempted(
                context,
                trace,
                target,
                wallX,
                wallZ,
                result.getHorizontalInputRadius(),
                player == null ? VanillaMath.sin(yaw) : VanillaMath.sin(player.getClientVersion(), yaw),
                player == null ? VanillaMath.cos(yaw) : VanillaMath.cos(player.getClientVersion(), yaw));

        KnownInput keys = reportedKeys(player);
        if (keys != null) {
            int strafe = (int) keys.strafeImpulse();
            int forward = (int) keys.forwardImpulse();
            // A minor collision never stops the sprint, from whichever accepted start the keys reach the delta.
            if (move.minor(strafe, forward) && reachesFromAcceptedStart(result, move, strafe, forward)) {
                return DesyncStatus.FALSE;
            }
            int reported = move.collisions(strafe, forward);
            if (reported != 0) return verdict(reported);
        }
        int collisions = 0;
        for (int strafe = -1; strafe <= 1; strafe++) {
            for (int forward = -1; forward <= 1; forward++) {
                collisions |= move.collisions(strafe, forward);
            }
        }
        return verdict(collisions);
    }

    private static boolean otherZeroFlagReality(PredictionResult result, PredictionResult reality) {
        return reality != result
                && reality.getFlagSeverity() == 0.0
                && reality.getSimulationContext() != null
                && reality.getValidMovements() != null;
    }

    /**
     * Whether the key direction (strafe, forward) reaches the reported delta from the selected movement's starts or
     * from those of any reality the prediction accepted, each the start of a client the move is consistent with.
     */
    private static boolean reachesFromAcceptedStart(PredictionResult result, Move move, int strafe, int forward) {
        if (move.reaches(strafe, forward, result.getValidMovements().getCollisionIgnoredMaxStartingVelExtents())) {
            return true;
        }
        List<PredictionResult> realities = result.getRealities();
        for (int i = 0, size = realities == null ? 0 : realities.size(); i < size; i++) {
            PredictionResult reality = realities.get(i);
            if (!otherZeroFlagReality(result, reality)) continue;
            MovementTrace trace = reality.getValidMovements().getClosestTrace();
            if (trace == null) continue;
            Move from = move.from(reality.getSimulationContext(), trace, reality.getHorizontalInputRadius());
            if (from.reaches(
                    strafe, forward, reality.getValidMovements().getCollisionIgnoredMaxStartingVelExtents())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The deltaMovement LivingEntity#handleOnClimbable left in the travel of {@code trace}, whose last vector is the
     * one it clamped, or null when that travel does not clamp before the movement Entity#move collides.
     */
    private static PredVector climbableClamp(SimulationContext context, MovementTrace trace) {
        if (context.getWorldData() == null
                || Ladder.clampsDeltaMovement(context) != DesyncStatus.TRUE
                || !(trace.preCollisionMovement() instanceof PredVector attempted)) return null;
        for (PredVector vector = attempted; vector != null; vector = vector.lastVector) {
            if (Ladder.CLAMP_REASON.equals(vector.reason)) return vector.lastVector == null ? null : vector;
        }
        return null;
    }

    /**
     * The keys LocalPlayer#sendChanges reported in PLAYER_INPUT before the move, which KeyboardInput#tick read for its
     * travel, or null when the client does not report the keys LocalPlayer#applyInput moves by.
     */
    private static KnownInput reportedKeys(CultPlayer player) {
        if (player == null
                || player.getClientVersion().isOlderThan(ClientVersion.V_1_21_2)
                || player.isBedrockMovement()
                || !player.cameraEntity.isSelf()) return null;
        return player.packetStateData.knownInput;
    }

    /**
     * Entity#move's horizontalCollision and minorHorizontalCollision for a forward move: Player#isHorizontalCollisionMinor
     * compares the collided movement with xxa/zza turned by the yaw. Gliding can leave those nonzero while adding
     * no moveRelative acceleration.
     */
    private static DesyncStatus forwardHard(PredictionResult result, JavaMoveCollision move) {
        if (!move.horizontalCollision()) return DesyncStatus.FALSE;
        return afterMove(result, move.attempted(), move.collided());
    }

    /** The travel's steering keys, including when its movement mode added zero acceleration. */
    public static DesyncStatus afterMove(PredictionResult result, Vec3 delta, Vec3 movement) {
        if (Math.abs(delta.x - movement.x) < 1.0E-5F && Math.abs(delta.z - movement.z) < 1.0E-5F)
            return DesyncStatus.FALSE;
        PredVector start = result.getInitialStartingVel();
        Vec3 input = start == null ? null : start.getTravelInput();
        if (input == null || input.horizontalDistanceSqr() != 0 || start.getCollisionInputs() == null)
            return afterMove(input, delta, movement);
        DesyncStatus verdict = null;
        for (Vec3 steering : start.getCollisionInputs()) {
            DesyncStatus candidate =
                    afterMove(steering.horizontalDistanceSqr() < 1.0E-5F ? Vec3.ZERO : steering, delta, movement);
            if (verdict != null && candidate != verdict) return DesyncStatus.UNKNOWN;
            verdict = candidate;
        }
        return verdict == null ? DesyncStatus.UNKNOWN : verdict;
    }

    /**
     * The flags one Entity#move other than the travel left, from its {@code delta} and collided {@code movement}:
     * horizontalCollision past Mth.equal's 1.0E-5F on X or Z, and minorHorizontalCollision from the travel's
     * {@code input} direction.
     */
    public static DesyncStatus afterMove(Vec3 input, Vec3 delta, Vec3 movement) {
        if (Math.abs(delta.x - movement.x) < 1.0E-5F && Math.abs(delta.z - movement.z) < 1.0E-5F)
            return DesyncStatus.FALSE;
        double length = input == null ? 0 : Math.sqrt(input.x * input.x + input.z * input.z);
        if (length == 0) return DesyncStatus.TRUE;
        return isMinor(input.x / length, input.z / length, movement.x, movement.z)
                ? DesyncStatus.FALSE
                : DesyncStatus.TRUE;
    }

    private static DesyncStatus verdict(int collisions) {
        return switch (collisions) {
            case HARD -> DesyncStatus.TRUE;
            case SOFT -> DesyncStatus.FALSE;
            default -> DesyncStatus.UNKNOWN;
        };
    }

    /** +1/-1 when the move ends flush against a wall on that side of the axis, else 0. */
    private static int wallSide(CollideAxisData.CollideResult result, double target) {
        if (result == null || !result.isLikelyCollide() || result.getSide() == 0) return 0;
        return Math.abs(result.getResult() - target) <= TOLERANCE ? result.getSide() : 0;
    }

    /**
     * The attempted movement {@code clamp(base + input * t) + after} of a move that reported {@code target}, each
     * horizontal axis clamped to its limit.
     */
    private record Move(
            Vec3 base,
            Vec3 target,
            int wallX,
            int wallZ,
            double stuckX,
            double stuckZ,
            double limitX,
            double limitZ,
            double afterX,
            double afterZ,
            double maxInput,
            double sin,
            double cos) {
        /**
         * The move that reported {@code target} attempted by the travel of {@code trace} in {@code context}: its
         * pre-collision movement scaled by the stuck multiplier, or, where LivingEntity#handleOnClimbable clamps, the
         * vector it clamped with the clamp as each axis's limit and what the travel adds after the clamp.
         */
        static Move attempted(
                SimulationContext context,
                MovementTrace trace,
                Vec3 target,
                int wallX,
                int wallZ,
                double maxInput,
                double sin,
                double cos) {
            Vec3 stuck = trace.position().collisionStuckSpeedMultiplier(context);
            double stuckX = stuck == null ? 1 : stuck.x;
            double stuckZ = stuck == null ? 1 : stuck.z;
            Vec3 base = trace.preCollisionMovement();
            double limitX = Double.POSITIVE_INFINITY;
            double limitZ = Double.POSITIVE_INFINITY;
            double afterX = 0;
            double afterZ = 0;
            PredVector clamped = climbableClamp(context, trace);
            if (clamped != null) {
                Vec3 unclamped = clamped.lastVector;
                afterX = base.x - clamped.x * stuckX;
                afterZ = base.z - clamped.z * stuckZ;
                base = new Vec3(unclamped.x * stuckX, base.y, unclamped.z * stuckZ);
                limitX = CLIMBABLE_LIMIT * stuckX;
                limitZ = CLIMBABLE_LIMIT * stuckZ;
            }
            return new Move(
                    base, target, wallX, wallZ, stuckX, stuckZ, limitX, limitZ, afterX, afterZ, maxInput, sin, cos);
        }

        /** The same reported move attempted by the travel of another accepted {@code trace} in {@code context}. */
        Move from(SimulationContext context, MovementTrace trace, double maxInput) {
            return attempted(context, trace, target, wallX, wallZ, maxInput, sin, cos);
        }

        /**
         * {@link #SOFT} and {@link #HARD} for the collisions the key direction (strafe, forward) can end in with an
         * input length that reaches the reported delta, 0 when no length does.
         */
        int collisions(int strafe, int forward) {
            double length = Math.sqrt(strafe * strafe + forward * forward);
            double dirX = length == 0 ? 0 : (strafe * cos - forward * sin) / length;
            double dirZ = length == 0 ? 0 : (forward * cos + strafe * sin) / length;
            Axis x = new Axis(base.x, dirX * stuckX, target.x, wallX, limitX, afterX);
            Axis z = new Axis(base.z, dirZ * stuckZ, target.z, wallZ, limitZ, afterZ);

            double[] consistent = {0, length == 0 ? 0 : maxInput};
            if (!x.consistent(consistent) || !z.consistent(consistent)) return 0;
            double low = consistent[0];
            double high = consistent[1];

            int collisions = 0;
            boolean minor = minor(strafe, forward);
            // Collision is certain where a wall axis overshoots by more than the tolerance, else it may not exist.
            double[] noCollision = {low, high};
            if (minor || x.reachesWithin(noCollision) && z.reachesWithin(noCollision)) collisions |= SOFT;
            if (!minor && (x.overshoots(new double[] {low, high}) || z.overshoots(new double[] {low, high}))) {
                collisions |= HARD;
            }
            return collisions;
        }

        /**
         * Whether the key direction (strafe, forward) reaches the reported delta with an input length up to the
         * allowance from some starting movement within {@code starts}, the accepted starting velocities with their
         * uncertainties, scaled by the stuck multiplier as Entity#move scales its attempted delta, or from the closest
         * trace's.
         */
        boolean reaches(int strafe, int forward, SimpleCollisionBox starts) {
            double length = Math.sqrt(strafe * strafe + forward * forward);
            if (length == 0) return false;
            double dirX = (strafe * cos - forward * sin) / length;
            double dirZ = (forward * cos + strafe * sin) / length;
            double[] consistent = {0, maxInput};
            Axis x = new Axis(base.x, dirX * stuckX, target.x, wallX, limitX, afterX);
            Axis z = new Axis(base.z, dirZ * stuckZ, target.z, wallZ, limitZ, afterZ);
            if (starts == null) return x.consistent(consistent) && z.consistent(consistent);
            return x.consistentFrom(consistent, starts.minX * stuckX, starts.maxX * stuckX)
                    && z.consistentFrom(consistent, starts.minZ * stuckZ, starts.maxZ * stuckZ);
        }

        /**
         * Whether a collision with the key direction (strafe, forward) is minor. The angle is measured on the collided
         * movement, which the reported delta fixes.
         */
        boolean minor(int strafe, int forward) {
            double length = Math.sqrt(strafe * strafe + forward * forward);
            if (length == 0) return false;
            return isMinor(
                    (strafe * cos - forward * sin) / length,
                    (forward * cos + strafe * sin) / length,
                    target.x,
                    target.z);
        }
    }

    /** The sine table's yaw direction is not of unit length, so the angle divides by both lengths. */
    private static boolean isMinor(double dirX, double dirZ, double movementX, double movementZ) {
        double inputLengthSquared = dirX * dirX + dirZ * dirZ;
        double movementLengthSquared = movementX * movementX + movementZ * movementZ;
        if (inputLengthSquared < 1.0E-5F || movementLengthSquared < 1.0E-5F) return false;
        double dot = dirX * movementX + dirZ * movementZ;
        return Math.acos(Math.min(1, dot / Math.sqrt(inputLengthSquared * movementLengthSquared))) < MINOR_ANGLE;
    }

    /**
     * One horizontal axis of the attempted movement {@code base + input * t}, clamped to {@code [-limit, limit]}, plus
     * {@code after}, against the reported delta.
     */
    private record Axis(double base, double input, double target, int wall, double limit, double after) {
        double at(double t) {
            return Math.max(-limit, Math.min(limit, base + input * t)) + after;
        }

        /** Narrows {@code range} to the input lengths that reach the reported delta, false if none do. */
        boolean consistent(double[] range) {
            // A free axis must match the delta; a wall axis must reach it and may push further into the wall.
            double min = wall == 0 ? target - TOLERANCE : wall > 0 ? target - TOLERANCE : Double.NEGATIVE_INFINITY;
            double max = wall == 0 ? target + TOLERANCE : wall > 0 ? Double.POSITIVE_INFINITY : target + TOLERANCE;
            return clamp(range, min, max);
        }

        /**
         * Narrows {@code range} to the input lengths that reach the reported delta from some start between this
         * axis's base and the span {@code [low, high]}, false if none do.
         */
        boolean consistentFrom(double[] range, double low, double high) {
            double from = Math.min(base, Math.min(low, high));
            double to = Math.max(base, Math.max(low, high));
            double min = unclampedMin(
                    wall == 0 ? target - TOLERANCE : wall > 0 ? target - TOLERANCE : Double.NEGATIVE_INFINITY);
            double max = unclampedMax(
                    wall == 0 ? target + TOLERANCE : wall > 0 ? Double.POSITIVE_INFINITY : target + TOLERANCE);
            if (min > max) return false;
            if (input == 0) return to >= min && from <= max && range[0] <= range[1];
            double first = (min - to) / input;
            double second = (max - from) / input;
            range[0] = Math.max(range[0], Math.min(first, second));
            range[1] = Math.min(range[1], Math.max(first, second));
            return range[0] <= range[1];
        }

        /** Narrows {@code range} to the input lengths that stay within the tolerance of a wall, false if none do. */
        boolean reachesWithin(double[] range) {
            return wall == 0 || clamp(range, target - TOLERANCE, target + TOLERANCE);
        }

        /** Narrows {@code range} to the input lengths that push past the wall by more than the tolerance. */
        boolean overshoots(double[] range) {
            if (wall == 0) return false;
            double min = wall > 0 ? Math.nextUp(target + TOLERANCE) : Double.NEGATIVE_INFINITY;
            double max = wall > 0 ? Double.POSITIVE_INFINITY : Math.nextDown(target - TOLERANCE);
            return clamp(range, min, max);
        }

        private boolean clamp(double[] range, double min, double max) {
            min = unclampedMin(min);
            max = unclampedMax(max);
            if (min > max) return false;
            if (input == 0) return base >= min && base <= max && range[0] <= range[1];
            double first = (min - base) / input;
            double second = (max - base) / input;
            range[0] = Math.max(range[0], Math.min(first, second));
            range[1] = Math.min(range[1], Math.max(first, second));
            return range[0] <= range[1];
        }

        /** The least unclamped value whose clamp plus {@code after} reaches {@code min}, positive infinity if none. */
        private double unclampedMin(double min) {
            min -= after;
            if (min > limit) return Double.POSITIVE_INFINITY;
            return min <= -limit ? Double.NEGATIVE_INFINITY : min;
        }

        /** The greatest unclamped value whose clamp plus {@code after} stays within {@code max}, else -infinity. */
        private double unclampedMax(double max) {
            max -= after;
            if (max < -limit) return Double.NEGATIVE_INFINITY;
            return max >= limit ? Double.POSITIVE_INFINITY : max;
        }
    }
}
