package ac.cult.cultac.checks.impl.prediction;

import net.minecraft.world.phys.Vec3;

public interface AuthoredMovementFrame {
    Vec3 getPosition();

    long getClientTick();

    boolean hasRotation();

    float getYaw();

    float getPitch();
}
