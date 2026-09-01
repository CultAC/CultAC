package ac.grim.grimac.utils.nmsutil;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;

/** Bridges Mojang's ResourceLocation-to-Identifier rename without leaking either type internally. */
public final class NmsIdentifierUtil {
    private NmsIdentifierUtil() {
    }

    public static String asString(Object identifier) {
        if (identifier == null) {
            return "minecraft:unknown";
        }
        return identifier.toString();
    }

    public static String resourceKey(Object resourceKey) {
        return asString(invokeNoArg(resourceKey, "identifier", "location"));
    }

    public static String cooldownGroup(Object packet) {
        return asString(invokeNoArg(packet, "cooldownGroup"));
    }

    public static String payloadId(Object payloadOrType) {
        return asString(invokeNoArg(payloadOrType, "id"));
    }

    public static String registryKey(Object registry, Object value) {
        if (registry == null || value == null) {
            return null;
        }
        try {
            Method method = registry.getClass().getMethod("getKey", Object.class);
            return asString(method.invoke(registry, value));
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new IllegalStateException("Unable to access registry key", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Registry key lookup failed", exception.getCause());
        }
    }

    public static String attributeModifierId(Object modifier) {
        return asString(invokeNoArg(modifier, "id"));
    }

    public static int cooldownDuration(Object packet) {
        return (int) invokeNoArg(packet, "duration");
    }

    public static String useCooldownGroup(Object useCooldown, String fallback) {
        if (useCooldown == null) {
            return fallback;
        }
        Object value = invokeNoArg(useCooldown, "cooldownGroup");
        if (value instanceof Optional<?> optional) {
            return optional.map(NmsIdentifierUtil::asString).orElse(fallback);
        }
        return asString(value);
    }

    private static Object invokeNoArg(Object target, String... methodNames) {
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        for (String methodName : methodNames) {
            try {
                Method method = target.getClass().getMethod(methodName);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
                // Try the name used by the other supported Mojang mapping generation.
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("Unable to access " + target.getClass().getName() + "#" + methodName, exception);
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("Identifier accessor failed", cause);
            }
        }
        throw new IllegalStateException("No supported identifier accessor on " + target.getClass().getName());
    }
}
