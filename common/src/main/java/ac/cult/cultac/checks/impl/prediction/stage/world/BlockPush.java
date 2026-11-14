package ac.cult.cultac.checks.impl.prediction.stage.world;

import ac.cult.cultac.protocol.value.Direction;

/**
 * LocalPlayer#aiStep's moveTowardsClosestSpace pushes: each sets deltaMovement on its axis to 0.1 toward the closest
 * side that does not suffocate, and a later probe's push replaces an earlier one on the same axis.
 */
public record BlockPush(boolean pushesX, double x, boolean pushesZ, double z) {
    public static final BlockPush NONE = new BlockPush(false, 0.0, false, 0.0);

    public boolean pushes() {
        return pushesX || pushesZ;
    }

    BlockPush then(Direction direction) {
        if (direction.getModX() != 0) return new BlockPush(true, 0.1 * direction.getModX(), pushesZ, z);
        return new BlockPush(pushesX, x, true, 0.1 * direction.getModZ());
    }
}
