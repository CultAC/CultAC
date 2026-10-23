package ac.cult.cultac.utils.data;

import ac.cult.cultac.utils.math.Vec3;

/**
 * What the Entity#move(MoverType.SHULKER_BOX) calls of one Level#tickBlockEntities leave in the local player: their
 * total movement, the Entity#deltaMovement axes their collisions zero, and the Entity#onGround the last call sets.
 */
public record ShulkerBoxShoveResult(Vec3 movement, boolean zeroX, boolean zeroY, boolean zeroZ, boolean onGround) {
    /** Entity#deltaMovement after the calls, from {@code velocity} before them. */
    public Vec3 velocityAfter(Vec3 velocity) {
        if (!zeroX && !zeroY && !zeroZ) return velocity;
        return new Vec3(zeroX ? 0.0D : velocity.x, zeroY ? 0.0D : velocity.y, zeroZ ? 0.0D : velocity.z);
    }
}
