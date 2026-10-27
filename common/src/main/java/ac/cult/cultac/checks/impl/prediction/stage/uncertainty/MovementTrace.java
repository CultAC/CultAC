package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPassAfterTravel;
import ac.cult.cultac.utils.math.Vec3;

/**
 * {@code passAfterTravel}: from 26.3, the block-entity pass between this travel and the report, when it moved the
 * player.
 */
public record MovementTrace(
        PredVector position,
        Vec3 positionOnlyDelta,
        Vec3 collisionBaseOffset,
        Vec3 preCollisionVelocity,
        Vec3 preCollisionMovement,
        JavaPassAfterTravel passAfterTravel) {
    public MovementTrace(
            PredVector position,
            Vec3 positionOnlyDelta,
            Vec3 collisionBaseOffset,
            Vec3 preCollisionVelocity,
            Vec3 preCollisionMovement) {
        this(position, positionOnlyDelta, collisionBaseOffset, preCollisionVelocity, preCollisionMovement, null);
    }

    public static MovementTrace start(PredVector position) {
        return new MovementTrace(position, Vec3.ZERO, Vec3.ZERO, position, position);
    }

    public MovementTrace withPosition(PredVector position) {
        if (position == this.position) return this;
        return new MovementTrace(
                position,
                positionOnlyDelta,
                collisionBaseOffset,
                preCollisionVelocity,
                preCollisionMovement,
                passAfterTravel);
    }

    /**
     * {@code position} after a handler moved the client's Entity#deltaMovement there: the pre-collision velocity moves
     * by the same amount. A handler whose movement is not deltaMovement (a step, an edge backoff, a shove) uses
     * {@link #withPosition} instead.
     */
    public MovementTrace withVelocityShift(PredVector position) {
        return withVelocityShift(position, null);
    }

    /** The same velocity adjustment in Entity#move's attempted delta, after its stuck multiplier. */
    public MovementTrace withVelocityShift(PredVector position, Vec3 movementScale) {
        if (position == this.position) return this;
        Vec3 velocity = preCollisionVelocity == this.position
                ? position
                : new Vec3(
                        shifted(preCollisionVelocity.x, this.position.x, position.x),
                        shifted(preCollisionVelocity.y, this.position.y, position.y),
                        shifted(preCollisionVelocity.z, this.position.z, position.z));
        Vec3 movementShift = position.subtract(this.position);
        if (movementScale != null) movementShift = movementShift.multiply(movementScale);
        Vec3 movement = movementShift.lengthSqr() == 0 ? preCollisionMovement : preCollisionMovement.add(movementShift);
        return new MovementTrace(position, positionOnlyDelta, collisionBaseOffset, velocity, movement, passAfterTravel);
    }

    // An axis on which the velocity still equals the position takes the new value exactly.
    private static double shifted(double velocity, double from, double to) {
        return velocity == from ? to : velocity + (to - from);
    }

    public MovementTrace withPreCollisionVelocity(Vec3 velocity) {
        if (velocity == preCollisionVelocity) return this;
        return new MovementTrace(
                position, positionOnlyDelta, collisionBaseOffset, velocity, preCollisionMovement, passAfterTravel);
    }

    public MovementTrace withPreCollisionMovement(Vec3 movement) {
        if (movement == preCollisionMovement) return this;
        return new MovementTrace(
                position, positionOnlyDelta, collisionBaseOffset, preCollisionVelocity, movement, passAfterTravel);
    }

    public MovementTrace addPositionOnlyDelta(Vec3 delta) {
        if (delta.lengthSqr() <= 1.0E-14) {
            return this;
        }
        return new MovementTrace(
                position,
                positionOnlyDelta.add(delta),
                collisionBaseOffset,
                preCollisionVelocity,
                preCollisionMovement,
                passAfterTravel);
    }

    public MovementTrace addCollisionBaseOffset(Vec3 delta) {
        if (delta.lengthSqr() <= 1.0E-14) {
            return this;
        }
        return new MovementTrace(
                position,
                positionOnlyDelta,
                collisionBaseOffset.add(delta),
                preCollisionVelocity,
                preCollisionMovement,
                passAfterTravel);
    }
}
