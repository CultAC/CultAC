package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.utils.data.ShulkerBoxShoveResult;
import ac.cult.cultac.utils.math.Vec3;

/** {@code shulkerBoxShove}: what the shulker box block shoves after the travel leave, when any reaches the player. */
public record MovementTrace(
        PredVector position,
        Vec3 positionOnlyDelta,
        Vec3 collisionBaseOffset,
        Vec3 preCollisionVelocity,
        Vec3 preCollisionMovement,
        ShulkerBoxShoveResult shulkerBoxShove) {
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
                shulkerBoxShove);
    }

    public MovementTrace withPreCollisionVelocity(Vec3 velocity) {
        if (velocity == preCollisionVelocity) return this;
        return new MovementTrace(
                position, positionOnlyDelta, collisionBaseOffset, velocity, preCollisionMovement, shulkerBoxShove);
    }

    public MovementTrace withPreCollisionMovement(Vec3 movement) {
        if (movement == preCollisionMovement) return this;
        return new MovementTrace(
                position, positionOnlyDelta, collisionBaseOffset, preCollisionVelocity, movement, shulkerBoxShove);
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
                shulkerBoxShove);
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
                shulkerBoxShove);
    }
}
