package ac.grim.grimac.network.protocol.util.viaversion;

import org.bukkit.Bukkit;

public final class ViaVersionUtil {
    private ViaVersionUtil() {
    }

    public static boolean isAvailable() {
        return Bukkit.getPluginManager().getPlugin("ViaVersion") != null;
    }
}
