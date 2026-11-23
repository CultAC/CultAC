package ac.cult.cultac.network.protocol.util.viaversion;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.utils.reflection.ReflectionUtils;

public final class ViaVersionUtil {
    private static volatile Boolean available;

    private ViaVersionUtil() {}

    public static boolean isAvailable() {
        Boolean cached = available;
        if (cached == null) {
            cached = CultAPI.INSTANCE.getPluginManager().getPlugin("ViaVersion") != null
                    && ReflectionUtils.hasClass("com.viaversion.viaversion.api.Via");
            available = cached;
        }
        return cached;
    }
}
