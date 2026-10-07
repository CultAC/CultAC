package ac.cult.cultac.checks.impl.prediction;

import ac.cult.cultac.utils.math.Vec3;

public interface AuthoredMovementFrame {
    Vec3 getPosition();

    long getClientTick();

    boolean hasRotation();

    float getYaw();

    float getPitch();
}
