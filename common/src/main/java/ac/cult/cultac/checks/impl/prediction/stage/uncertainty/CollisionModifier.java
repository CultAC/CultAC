package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;

public final class CollisionModifier {
    private CollisionModifier() {}

    /**
     * Clips {@code start} like Entity#collide, then applies Entity#move's setPos guard. The guard sees the
     * horizontal input that {@code inputSource}'s circular input allowance adds towards {@code end}; a null
     * {@code inputSource} means no input.
     */
    public static PredVector transformWithCollisions(
            SimulationContext context,
            CollideAxisData collide,
            PredVector start,
            Vec3 end,
            PredictionResult inputSource) {
        PredVector attempted = start;
        // A Java vehicle's move is clipped axis by axis.
        if (!isJavaVehicleMove(context)) {
            start = clampWithinCollisions(collide, start, end);
        } else {
            start = clipExactly(collide, start);
        }
        Vec3 stuckSpeed = attempted.collisionStuckSpeedMultiplier(context);
        if (applyEntityMovePositionCommitGuard(attempted, start, stuckSpeed) == start) return start;
        // LivingEntity#travel adds moveRelative's input before Entity#move; the guard measures the full delta.
        Vec3 input = horizontalInputTowards(start, end, inputSource);
        if (input != Vec3.ZERO
                && applyEntityMovePositionCommitGuard(attempted.add(input), start.add(input), stuckSpeed)
                        != Vec3.ZERO) {
            return start;
        }
        return start.with(Vec3.ZERO, "Entity#move setPos guard");
    }

    /** Whether {@code context} moves a vehicle a Java client controls, which Entity#collide clips axis by axis. */
    public static boolean isJavaVehicleMove(SimulationContext context) {
        return context.getVehicle() != null && context.getBedrockInput() == null;
    }

    private static Vec3 horizontalInputTowards(Vec3 movement, Vec3 end, PredictionResult inputSource) {
        if (inputSource == null || inputSource.getSimulationContext() == null) return Vec3.ZERO;
        double dx = end.x - movement.x;
        double dz = end.z - movement.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (!Double.isFinite(distance) || distance < 1.0E-5F) return Vec3.ZERO;
        double radius = inputSource.getHorizontalInputRadius();
        if (!(radius > 0.0D)) return Vec3.ZERO;
        double scale = Math.min(radius, distance) / distance;
        return new Vec3(dx * scale, 0.0D, dz * scale);
    }

    private static Vec3 applyEntityMovePositionCommitGuard(
            Vec3 attemptedMovement, Vec3 clippedMovement, Vec3 stuckSpeed) {
        double mx = stuckSpeed == null ? 1.0D : stuckSpeed.x;
        double my = stuckSpeed == null ? 1.0D : stuckSpeed.y;
        double mz = stuckSpeed == null ? 1.0D : stuckSpeed.z;
        double ax = attemptedMovement.x * mx, ay = attemptedMovement.y * my, az = attemptedMovement.z * mz;
        double cx = clippedMovement.x * mx, cy = clippedMovement.y * my, cz = clippedMovement.z * mz;
        double clippedLengthSqr = cx * cx + cy * cy + cz * cz;
        if (clippedLengthSqr <= 1.0E-7D && ax * ax + ay * ay + az * az - clippedLengthSqr >= 1.0E-7D) {
            return Vec3.ZERO;
        }

        return clippedMovement;
    }

    private static PredVector clampWithinCollisions(CollideAxisData collide, PredVector start, Vec3 end) {
        // Collisions mean that movement from the start of the collision to the max extent is possible
        if (isValidCollision(collide.getX(), start.x, end.x)) {
            double clamped = CultMath.clamp(end.x, start.x, collide.getX().getResult());
            start = start.withXYZOrSelf(clamped, start.y, start.z, "collide x");
        }

        if (isValidCollision(collide.getZ(), start.z, end.z)) {
            double clamped = CultMath.clamp(end.z, start.z, collide.getZ().getResult());
            start = start.withXYZOrSelf(start.x, start.y, clamped, "collide z");
        }

        if (collide.getYNeg() != null
                && collide.getYNeg().isLikelyCollide()
                && start.y < 0
                && start.y < collide.getYNeg().getResult()
                // VoxelShape#collideX keeps a floor up to 1e-7 above the feet, so a fall can clip slightly upward.
                && end.y <= Math.max(0.0D, collide.getYNeg().getResult())
                // At the start's own Y a clip is possible; above it, one is forced.
                && end.y >= start.y) {
            double clamped = CultMath.clamp(end.y, start.y, collide.getYNeg().getResult());
            start = start.withY(clamped, "collide -y");
        }

        if (collide.getYPos() != null
                && collide.getYPos().isLikelyCollide()
                && start.y > 0
                && start.y > collide.getYPos().getResult()
                // Mirrored: a ceiling up to 1e-7 below the head can clip a rise slightly downward.
                && end.y >= Math.min(0.0D, collide.getYPos().getResult())
                && end.y <= start.y) {
            double clamped = CultMath.clamp(end.y, start.y, collide.getYPos().getResult());
            start = start.withY(clamped, "collide +y");
        }
        return start;
    }

    /** Entity#collide: the move along each axis stops at the first shape in its way. */
    private static PredVector clipExactly(CollideAxisData collide, PredVector movement) {
        movement = movement.withXYZOrSelf(movement.x, clipY(collide, movement.y), movement.z, "collide y");
        movement = movement.withXYZOrSelf(clip(collide.getX(), movement.x), movement.y, movement.z, "collide x");
        return movement.withXYZOrSelf(movement.x, movement.y, clip(collide.getZ(), movement.z), "collide z");
    }

    private static double clipY(CollideAxisData collide, double movement) {
        CollideAxisData.CollideResult floor = collide.getYNeg();
        if (movement < 0.0D && floor != null && floor.isLikelyCollide() && movement < floor.getResult()) {
            return floor.getResult();
        }
        CollideAxisData.CollideResult ceiling = collide.getYPos();
        if (movement > 0.0D && ceiling != null && ceiling.isLikelyCollide() && movement > ceiling.getResult()) {
            return ceiling.getResult();
        }
        return movement;
    }

    /** {@code movement} along one horizontal axis, stopped at the shape {@code data} found on its side. */
    private static double clip(CollideAxisData.CollideResult data, double movement) {
        if (!data.isLikelyCollide() || Math.signum(movement) != data.getSide()) return movement;
        return Math.abs(movement) < Math.abs(data.getResult()) ? movement : data.getResult();
    }

    private static boolean isValidCollision(CollideAxisData.CollideResult data, double start, double end) {
        if (!data.isLikelyCollide()) return false;
        // The player wouldn't hit the collision yet with their regular velocity
        if (Math.abs(start) < Math.abs(data.getResult())) return false;
        // The signs are correct for a collision to occur in this direction
        // TODO: Differentiate between positive and negative collisions
        return true;
    }
}
