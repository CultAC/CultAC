package ac.cult.cultac.network.protocol.util;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;

public final class SpigotReflectionUtil {
    private SpigotReflectionUtil() {}

    public static Object getMinecraftServerConnectionInstance() {
        try {
            Method getServer = Bukkit.getServer().getClass().getMethod("getServer");
            Object minecraftServer = getServer.invoke(Bukkit.getServer());
            if (minecraftServer == null) {
                return null;
            }
            Method getConnection = minecraftServer.getClass().getMethod("getConnection");
            return getConnection.invoke(minecraftServer);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
