package ac.cult.cultac.checks.impl.prediction;

import ac.cult.cultac.checks.impl.prediction.checks.HorizontalAnalyzer;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.math.VanillaMath;
import ac.cult.cultac.utils.math.Vec3;

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
 * LivingEntity#moveRelative do.
 */
public final class ClientHorizontalCollision {
    private static final double TOLERANCE = HorizontalAnalyzer.HORIZONTAL_INPUT_UNCERTAINTY;
    private static final double MINOR_ANGLE = 0.13962634F;
    private static final int SOFT = 1;
    private static final int HARD = 2;

    private ClientHorizontalCollision() {}

    /** TRUE only when every key input consistent with the accepted move ends in a hard collision. */
    public static DesyncStatus hard(PredictionResult result) {
        SimulationContext context = result.getSimulationContext();
        if (context == null
                || context.getVehicle() != null
                || result.getValidMovements() == null
                || result.getCollideAxisData() == null
                || result.getInitialStartingVel().isTickSkip()) return DesyncStatus.UNKNOWN;
        MovementTrace trace = result.getValidMovements().getClosestTrace();
        if (trace == null) return DesyncStatus.UNKNOWN;

        Vec3 target = result.getTarget();
        CollideAxisData collide = result.getCollideAxisData();
        int wallX = wallSide(collide.getX(), target.x);
        int wallZ = wallSide(collide.getZ(), target.z);
        if (wallX == 0 && wallZ == 0) return DesyncStatus.FALSE;

        Vec3 stuck = trace.position().collisionStuckSpeedMultiplier(context);
        float yaw = context.getXRot() * (float) (Math.PI / 180.0);
        CultPlayer player = result.getPlayer();
        Move move = new Move(
                trace.preCollisionMovement(),
                target,
                wallX,
                wallZ,
                stuck == null ? 1 : stuck.x,
                stuck == null ? 1 : stuck.z,
                result.getHorizontalInputRadius(),
                player == null ? VanillaMath.sin(yaw) : VanillaMath.sin(player.getClientVersion(), yaw),
                player == null ? VanillaMath.cos(yaw) : VanillaMath.cos(player.getClientVersion(), yaw));

        KnownInput keys = reportedKeys(player);
        if (keys != null) {
            int strafe = (int) keys.strafeImpulse();
            int forward = (int) keys.forwardImpulse();
            // A minor collision never stops the sprint, from whichever accepted start the keys reach the delta.
            if (move.minor(strafe, forward)
                    && move.reaches(
                            strafe, forward, result.getValidMovements().getCollisionIgnoredMaxStartingVelExtents())) {
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

    /** The attempted movement {@code base + input * t} of a move that reported {@code target}. */
    private record Move(
            Vec3 base,
            Vec3 target,
            int wallX,
            int wallZ,
            double stuckX,
            double stuckZ,
            double maxInput,
            double sin,
            double cos) {
        /**
         * {@link #SOFT} and {@link #HARD} for the collisions the key direction (strafe, forward) can end in with an
         * input length that reaches the reported delta, 0 when no length does.
         */
        int collisions(int strafe, int forward) {
            double length = Math.sqrt(strafe * strafe + forward * forward);
            double dirX = length == 0 ? 0 : (strafe * cos - forward * sin) / length;
            double dirZ = length == 0 ? 0 : (forward * cos + strafe * sin) / length;
            Axis x = new Axis(base.x, dirX * stuckX, target.x, wallX);
            Axis z = new Axis(base.z, dirZ * stuckZ, target.z, wallZ);

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
            Axis x = new Axis(base.x, dirX * stuckX, target.x, wallX);
            Axis z = new Axis(base.z, dirZ * stuckZ, target.z, wallZ);
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
                    (strafe * cos - forward * sin) / length, (forward * cos + strafe * sin) / length, target.x, target.z);
        }
    }

    private static boolean isMinor(double dirX, double dirZ, double movementX, double movementZ) {
        double movementLengthSquared = movementX * movementX + movementZ * movementZ;
        if (movementLengthSquared < 1.0E-5F) return false;
        double dot = dirX * movementX + dirZ * movementZ;
        return Math.acos(Math.min(1, dot / Math.sqrt(movementLengthSquared))) < MINOR_ANGLE;
    }

    /** One horizontal axis of the attempted movement {@code base + input * t} against the reported delta. */
    private record Axis(double base, double input, double target, int wall) {
        double at(double t) {
            return base + input * t;
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
            double min = wall == 0 ? target - TOLERANCE : wall > 0 ? target - TOLERANCE : Double.NEGATIVE_INFINITY;
            double max = wall == 0 ? target + TOLERANCE : wall > 0 ? Double.POSITIVE_INFINITY : target + TOLERANCE;
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
            if (input == 0) return base >= min && base <= max && range[0] <= range[1];
            double first = (min - base) / input;
            double second = (max - base) / input;
            range[0] = Math.max(range[0], Math.min(first, second));
            range[1] = Math.min(range[1], Math.max(first, second));
            return range[0] <= range[1];
        }
    }
}
