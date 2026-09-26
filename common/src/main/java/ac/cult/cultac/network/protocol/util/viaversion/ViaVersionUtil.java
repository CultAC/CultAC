package ac.cult.cultac.network.protocol.util.viaversion;

import ac.cult.cultac.utils.reflection.ReflectionUtils;
import org.bukkit.Bukkit;

public final class ViaVersionUtil {
    private static volatile Boolean available;

    private ViaVersionUtil() {
    }

    public static boolean isAvailable() {
        Boolean cached = available;
        if (cached == null) {
            cached = Bukkit.getPluginManager().getPlugin("ViaVersion") != null
                    && ReflectionUtils.hasClass("com.viaversion.viaversion.api.Via");
            available = cached;
        }
        return cached;
    }

    public static boolean isLegacyApiInstalled() {
        return !isAvailable() && ReflectionUtils.hasClass("us.myles.ViaVersion.api.Via");
    }
}
