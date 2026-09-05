package ac.cult.cultac.utils.floodgate;

import ac.cult.cultac.utils.anticheat.LogUtil;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.configuration.GeyserConfig;

public final class GeyserUtil {
    private GeyserUtil() {
    }

    public static boolean isGeyserPlayer(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        return lookupGeyserConnection(uuid);
    }

    private static boolean lookupGeyserConnection(UUID uuid) {
        if (!isGeyserPluginEnabled()) {
            return false;
        }
        try {
            return GeyserApi.api().connectionByUuid(uuid) != null;
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    public static boolean isGeyserAvailable() {
        if (!isGeyserPluginEnabled()) {
            return false;
        }

        try {
            GeyserApi.api();
            return true;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    public static void forceForwardPlayerPing() {
        Object gameplayConfig = GeyserImpl.getInstance().config().gameplay();
        if (isForwardPlayerPingEnabled(gameplayConfig)) {
            return;
        }

        try {
            Field field = findField(gameplayConfig.getClass(), "forwardPlayerPing");
            field.setAccessible(true);
            field.setBoolean(gameplayConfig, true);
            if (isForwardPlayerPingEnabled(gameplayConfig)) {
                LogUtil.info("CultAC forced Geyser forward-player-ping=true for Bedrock transaction validation.");
                return;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }

        if (tryBooleanSetter(gameplayConfig, "forwardPlayerPing") || tryBooleanSetter(gameplayConfig, "setForwardPlayerPing")) {
            LogUtil.info("CultAC forced Geyser forward-player-ping=true for Bedrock transaction validation.");
            return;
        }

        LogUtil.warn("Unable to force Geyser forward-player-ping=true; Bedrock transaction pongs may be Geyser-backed.");
    }

    private static boolean isForwardPlayerPingEnabled(Object gameplayConfig) {
        return gameplayConfig instanceof GeyserConfig.GameplayConfig config && config.forwardPlayerPing();
    }

    private static boolean tryBooleanSetter(Object target, String methodName) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName, boolean.class);
            method.setAccessible(true);
            method.invoke(target, true);
            return isForwardPlayerPingEnabled(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static boolean isGeyserPluginEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("Geyser-Spigot");
    }
}
