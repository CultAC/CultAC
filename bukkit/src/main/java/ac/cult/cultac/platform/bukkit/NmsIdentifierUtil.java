package ac.cult.cultac.platform.bukkit;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Bridges Mojang's ResourceLocation-to-Identifier rename without leaking either type internally. */
public final class NmsIdentifierUtil {
    private static final ClassValue<Method> REGISTRY_KEYS = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> type) {
            try {
                return type.getMethod("getKey", Object.class);
            } catch (NoSuchMethodException failure) {
                throw new IllegalStateException("Registry key is unavailable", failure);
            }
        }
    };

    private NmsIdentifierUtil() {}

    private static String asString(Object identifier) {
        if (identifier == null) {
            return "minecraft:unknown";
        }
        return identifier.toString();
    }

    public static String resourceKey(Object resourceKey) {
        return asString(invokeNoArg(resourceKey, "identifier", "location"));
    }

    public static String registryKey(Object registry, Object value) {
        if (registry == null || value == null) {
            return null;
        }
        try {
            Method method = REGISTRY_KEYS.get(registry.getClass());
            return asString(method.invoke(registry, value));
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Unable to access registry key", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Registry key lookup failed", exception.getCause());
        }
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
                throw new IllegalStateException(
                        "Unable to access " + target.getClass().getName() + "#" + methodName, exception);
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("Identifier accessor failed", cause);
            }
        }
        throw new IllegalStateException(
                "No supported identifier accessor on " + target.getClass().getName());
    }
}
