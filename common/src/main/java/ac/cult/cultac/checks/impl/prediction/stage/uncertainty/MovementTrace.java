package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import net.minecraft.world.phys.Vec3;

public record MovementTrace(PredVector position, Vec3 positionOnlyDelta, Vec3 collisionBaseOffset,
                            Vec3 preCollisionVelocity, Vec3 preCollisionMovement) {
    public static MovementTrace start(PredVector position) {
        return new MovementTrace(position, Vec3.ZERO, Vec3.ZERO, position, position);
    }

    public MovementTrace withPosition(PredVector position) {
        return new MovementTrace(position, positionOnlyDelta, collisionBaseOffset, preCollisionVelocity, preCollisionMovement);
    }

    public MovementTrace withPreCollisionVelocity(Vec3 velocity) {
        return new MovementTrace(position, positionOnlyDelta, collisionBaseOffset, velocity, preCollisionMovement);
    }

    public MovementTrace withPreCollisionMovement(Vec3 movement) {
        return new MovementTrace(position, positionOnlyDelta, collisionBaseOffset, preCollisionVelocity, movement);
    }

    public MovementTrace addPositionOnlyDelta(Vec3 delta) {
        if (delta.lengthSqr() <= 1.0E-14) {
            return this;
        }
        return new MovementTrace(position, positionOnlyDelta.add(delta), collisionBaseOffset, preCollisionVelocity, preCollisionMovement);
    }

    public MovementTrace addCollisionBaseOffset(Vec3 delta) {
        if (delta.lengthSqr() <= 1.0E-14) {
            return this;
        }
        return new MovementTrace(position, positionOnlyDelta, collisionBaseOffset.add(delta), preCollisionVelocity, preCollisionMovement);
    }
}
