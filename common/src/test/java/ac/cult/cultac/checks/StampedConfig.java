package ac.cult.cultac.checks;

import ac.grim.grimac.api.config.ConfigManager;
import java.lang.reflect.Proxy;
import java.util.Map;

/** A config holding only the given keys; every other lookup answers its default. */
public final class StampedConfig {
    private StampedConfig() {}

    public static ConfigManager of(Map<String, Object> values) {
        return (ConfigManager) Proxy.newProxyInstance(
                ConfigManager.class.getClassLoader(), new Class<?>[] {ConfigManager.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.endsWith("Else") && args != null && args.length == 2) {
                        Object value = values.get(args[0]);
                        return value != null ? value : args[1];
                    }
                    if (name.equals("hashCode")) return System.identityHashCode(proxy);
                    if (name.equals("equals")) return proxy == args[0];
                    if (name.equals("toString")) return "StampedConfig" + values;
                    if (method.getReturnType() == boolean.class) return false;
                    return null;
                });
    }

    /** A config whose {@code config.yml} and {@code punishments.yml} are both written in {@code nameVersion}. */
    public static ConfigManager nameVersion(int nameVersion) {
        return of(Map.of(CheckNames.CONFIG_KEY, nameVersion, CheckNames.PUNISHMENTS_KEY, nameVersion));
    }
}
