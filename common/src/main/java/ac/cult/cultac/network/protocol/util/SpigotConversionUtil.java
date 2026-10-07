package ac.cult.cultac.network.protocol.util;

import ac.cult.cultac.utils.math.Vec3;

public final class SpigotConversionUtil {
    private SpigotConversionUtil() {}

    public static Vec3 fromProtocolVec(ac.cult.cultac.protocol.value.Vec3d vector) {
        return new Vec3(vector.x(), vector.y(), vector.z());
    }
}
