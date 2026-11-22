package ac.cult.reload.host;

import ac.cult.reload.api.HostContext;
import ac.grim.grimac.internal.platform.bukkit.resolver.BukkitResolverRegistrar;
import ac.grim.grimac.internal.plugin.resolver.GrimExtensionManager;
import java.nio.file.Path;
import java.util.Optional;
import java.util.WeakHashMap;

public final class SharedExtensions {
    private SharedExtensions() {}

    public static GrimExtensionManager create(HostContext host) {
        var manager = new GrimExtensionManager();
        if (host.platform().equals("paper")) new BukkitResolverRegistrar().registerAll(manager);
        else {
            Object plugin = host.plugin();
            Object proxy = host.server();
            Path directory = host.directory();
            Object logger = host.logger();
            var wrapper = SharedPlugin.create(host.directory(), host.logger(), host.build());
            var owners = new WeakHashMap<Object, SharedPlugin>();
            manager.registerResolver(context -> {
                if (context == plugin) return wrapper;
                if (context.getClass().getClassLoader() instanceof RuntimeClassLoader || context instanceof Class<?>)
                    return null;
                synchronized (owners) {
                    var known = owners.get(context);
                    if (known != null) return known;
                    try {
                        var loader = proxy.getClass().getClassLoader();
                        var serverType = loader.loadClass("com.velocitypowered.api.proxy.ProxyServer");
                        var pluginsType = loader.loadClass("com.velocitypowered.api.plugin.PluginManager");
                        var containerType = loader.loadClass("com.velocitypowered.api.plugin.PluginContainer");
                        var descriptionType = loader.loadClass("com.velocitypowered.api.plugin.PluginDescription");
                        Object plugins =
                                serverType.getMethod("getPluginManager").invoke(proxy);
                        var container = (Optional<?>) pluginsType
                                .getMethod("fromInstance", Object.class)
                                .invoke(plugins, context);
                        if (container.isEmpty()) return null;
                        Object description =
                                containerType.getMethod("getDescription").invoke(container.get());
                        String id = (String) descriptionType.getMethod("getId").invoke(description);
                        var version = (Optional<?>)
                                descriptionType.getMethod("getVersion").invoke(description);
                        var resolved = SharedPlugin.create(
                                directory.resolveSibling(id),
                                logger,
                                version.map(Object::toString).orElse("unknown"));
                        owners.put(context, resolved);
                        return resolved;
                    } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException("Cannot resolve Velocity plugin", failure);
                    }
                }
            });
        }
        return manager;
    }
}
