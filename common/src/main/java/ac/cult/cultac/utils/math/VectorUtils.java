package ac.cult.cultac.utils.math;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

@UtilityClass
public class VectorUtils {
    // Sentinel velocity magnitude used when probing the extreme corners of the valid
    // starting-velocity envelope; large enough to exceed any legitimate movement.
    public static final double LARGE_MOVEMENT = 1000;

    @Contract("_, _ -> new")
    public static @NotNull Vector3dm cutBoxToVector(@NotNull Vector3dm vectorCutTo, @NotNull SimpleCollisionBox box) {
        return cutBoxToVector(vectorCutTo.getX(), vectorCutTo.getY(), vectorCutTo.getZ(), box);
    }

    public static @NotNull Vector3dm cutBoxToVector(double x, double y, double z, @NotNull SimpleCollisionBox box) {
        return new Vector3dm(
                CultMath.clamp(x, box.minX, box.maxX),
                CultMath.clamp(y, box.minY, box.maxY),
                CultMath.clamp(z, box.minZ, box.maxZ));
    }

    @Contract("_, _ -> new")
    public static @NotNull Vec3 cutBoxToVector(@NotNull Vec3 vectorCutTo, @NotNull SimpleCollisionBox box) {
        return new Vec3(
                CultMath.clamp(vectorCutTo.x, box.minX, box.maxX),
                CultMath.clamp(vectorCutTo.y, box.minY, box.maxY),
                CultMath.clamp(vectorCutTo.z, box.minZ, box.maxZ));
    }

    // Clamping stops the player from causing an integer overflow and crashing the netty thread
    @Contract("_ -> new")
    public static @NotNull Vec3 clampVector(@NotNull Vec3 toClamp) {
        double x = CultMath.clamp(toClamp.x, -3.0E7D, 3.0E7D);
        double y = CultMath.clamp(toClamp.y, -2.0E7D, 2.0E7D);
        double z = CultMath.clamp(toClamp.z, -3.0E7D, 3.0E7D);

        return new Vec3(x, y, z);
    }
}
