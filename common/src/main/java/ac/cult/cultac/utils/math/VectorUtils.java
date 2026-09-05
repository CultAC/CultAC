package ac.cult.cultac.utils.math;

import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import lombok.experimental.UtilityClass;
import net.minecraft.world.phys.Vec3;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

@UtilityClass
public class VectorUtils {
    // Sentinel velocity magnitude used when probing the extreme corners of the valid
    // starting-velocity envelope; large enough to exceed any legitimate movement.
    public static final double LARGE_MOVEMENT = 1000;

    public static @NotNull Vector3dm cutBoxToVector(@NotNull Vector3dm vectorToCutTo, @NotNull Vector3dm min, @NotNull Vector3dm max) {
        SimpleCollisionBox box = new SimpleCollisionBox(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ()).sort();
        return cutBoxToVector(vectorToCutTo, box);
    }

    @Contract("_, _ -> new")
    public static @NotNull Vector3dm cutBoxToVector(@NotNull Vector3dm vectorCutTo, @NotNull SimpleCollisionBox box) {
        return cutBoxToVector(vectorCutTo.getX(), vectorCutTo.getY(), vectorCutTo.getZ(), box);
    }

    public static @NotNull Vector3dm cutBoxToVector(double x, double y, double z, @NotNull SimpleCollisionBox box) {
        return new Vector3dm(CultMath.clamp(x, box.minX, box.maxX),
                CultMath.clamp(y, box.minY, box.maxY),
                CultMath.clamp(z, box.minZ, box.maxZ));
    }

    @Contract("_, _ -> new")
    public static @NotNull Vec3 cutBoxToVector(@NotNull Vec3 vectorCutTo, @NotNull SimpleCollisionBox box) {
        return new Vec3(CultMath.clamp(vectorCutTo.x, box.minX, box.maxX),
                CultMath.clamp(vectorCutTo.y, box.minY, box.maxY),
                CultMath.clamp(vectorCutTo.z, box.minZ, box.maxZ));
    }

    @Contract("_, _ -> new")
    public static @NotNull Vector cutBoxToVector(@NotNull Vector vectorCutTo, @NotNull SimpleCollisionBox box) {
        return new Vector(CultMath.clamp(vectorCutTo.getX(), box.minX, box.maxX),
                CultMath.clamp(vectorCutTo.getY(), box.minY, box.maxY),
                CultMath.clamp(vectorCutTo.getZ(), box.minZ, box.maxZ));
    }

    // Clamping stops the player from causing an integer overflow and crashing the netty thread
    @Contract("_ -> new")
    public static @NotNull Vec3 clampVector(@NotNull Vec3 toClamp) {
        double x = CultMath.clamp(toClamp.x, -3.0E7D, 3.0E7D);
        double y = CultMath.clamp(toClamp.y, -2.0E7D, 2.0E7D);
        double z = CultMath.clamp(toClamp.z, -3.0E7D, 3.0E7D);

        return new Vec3(x, y, z);
    }

    public static Vector3dm normalize(CultPlayer player, Vector3dm vec) {
        return normalize(player.getClientVersion(), vec);
    }

    public static Vector3dm normalize(ClientVersion version, Vector3dm vec) {
        double d0 = getVanillaLength(version, vec);
        return version.isNewerThanOrEquals(ClientVersion.V_1_21_2) ? modern$normalize(vec, d0) : legacy$normalize(vec, d0);
    }

    public static double getVanillaLength(ClientVersion version, Vector3dm vec) {
        return getVanillaLength(version, vec.getX(), vec.getY(), vec.getZ());
    }

    public static double getVanillaLength(ClientVersion version, double x, double y, double z) {
        double lengthSquared = x * x + y * y + z * z;
        return version.getProtocolVersion() < 755 ? (float) Math.sqrt(lengthSquared) : Math.sqrt(lengthSquared); // PE ClientVersion.V_1_17
    }

    private static Vector3dm legacy$normalize(Vector3dm vec, double d0) {
        return d0 < 1.0E-4D ? new Vector3dm() : new Vector3dm(vec.getX() / d0, vec.getY() / d0, vec.getZ() / d0);
    }

    private static Vector3dm modern$normalize(Vector3dm vec, double d0) {
        return d0 < 1.0E-5F ? new Vector3dm() : new Vector3dm(vec.getX() / d0, vec.getY() / d0, vec.getZ() / d0);
    }

}
