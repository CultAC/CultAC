package ac.grim.grimac.network.protocol.util;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

public final class SpigotReflectionUtil {
    public static final Class<?> ENTITY_PLAYER_CLASS = resolveClass("net.minecraft.server.level.ServerPlayer", "net.minecraft.world.entity.player.Player");
    public static final Class<?> NMS_ITEM_STACK_CLASS = resolveClass("net.minecraft.world.item.ItemStack", "net.minecraft.world.item.ItemStack");

    private SpigotReflectionUtil() {
    }

    public static double getTPS() {
        try {
            Method getTPS = Server.class.getMethod("getTPS");
            Object value = getTPS.invoke(Bukkit.getServer());
            if (value instanceof double[] tps && tps.length > 0) {
                return tps[0];
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return 20.0D;
    }

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

    public static Class<?> getServerClass(String modernName, String legacyName) {
        String[] candidates = {
                "net.minecraft." + modernName,
                "net.minecraft." + legacyName,
                modernName,
                legacyName
        };
        for (String candidate : candidates) {
            try {
                return Class.forName(candidate);
            } catch (ClassNotFoundException ignored) {
            }
        }
        throw new IllegalArgumentException("Unable to resolve server class for " + modernName + " / " + legacyName);
    }

    public static Object getNMSEntity(Player player) {
        try {
            Method getHandle = player.getClass().getMethod("getHandle");
            return getHandle.invoke(player);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to resolve NMS entity for " + player.getName(), exception);
        }
    }

    private static Class<?> resolveClass(String modernName, String fallbackName) {
        try {
            return Class.forName(modernName);
        } catch (ClassNotFoundException ignored) {
            try {
                return Class.forName(fallbackName);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Unable to resolve class " + modernName + " or " + fallbackName, e);
            }
        }
    }
}
