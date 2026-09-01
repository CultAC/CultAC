package ac.grim.grimac.utils.nmsutil;

import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.world.phys.Vec3;

public final class FluidFallingAdjustedMovement {
    private FluidFallingAdjustedMovement() {
    }

    public static Vec3 getFluidFallingAdjustedMovement(GrimPlayer player, double gravity, boolean falling, Vec3 movement) {
        if (gravity == 0 || player.isSprinting) {
            return movement;
        }

        double y;
        if (falling && Math.abs(movement.y - 0.005D) >= 0.003D && Math.abs(movement.y - gravity / 16.0D) < 0.003D) {
            y = -0.003D;
        } else {
            y = movement.y - gravity / 16.0D;
        }
        return new Vec3(movement.x, y, movement.z);
    }
}
