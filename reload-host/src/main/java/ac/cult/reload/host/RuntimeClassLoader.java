package ac.cult.reload.host;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;

/** Private implementation classes have no parent fallback, even on the first generation. */
public final class RuntimeClassLoader extends URLClassLoader {
    static {
        registerAsParallelCapable();
    }

    public RuntimeClassLoader(URL artifact, ClassLoader parent) {
        super(new URL[] {artifact}, parent);
    }

    private static boolean privateClass(String name) {
        return name.startsWith("ac.cult.cultac.") || name.startsWith("ac.cult.blocksim.");
    }

    private static boolean sharedClass(String name) {
        if (name.startsWith("ac.cult.cultac.manager.datastore.")) {
            String simple = name.substring(name.lastIndexOf('.') + 1).split("\\$", 2)[0];
            return !Set.of("DataStoreLifecycle", "LiveWriteHooks", "LiveWriteHooksImpl", "ClientVersionResolver")
                    .contains(simple);
        }
        return name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("jdk.")
                || name.startsWith("ac.cult.reload.")
                || name.startsWith("ac.grim.grimac.")
                || name.startsWith("org.bukkit.")
                || name.startsWith("io.papermc.paper.")
                || name.startsWith("net.minecraft.")
                || name.startsWith("com.mojang.")
                || name.startsWith("com.velocitypowered.")
                || name.startsWith("io.netty.")
                || name.startsWith("com.google.gson.")
                || name.startsWith("com.google.common.")
                || name.startsWith("it.unimi.dsi.fastutil.")
                || name.startsWith("org.joml.")
                || name.startsWith("net.kyori.")
                || name.startsWith("org.slf4j.")
                || name.startsWith("org.geysermc.")
                || name.startsWith("org.cloudburstmc.")
                || name.startsWith("com.nukkitx.")
                || name.startsWith("com.viaversion.")
                || name.startsWith("com.zaxxer.")
                || name.startsWith("org.sqlite.")
                || name.startsWith("com.mongodb.")
                || name.startsWith("org.bson.")
                || name.startsWith("net.luckperms.")
                || name.startsWith("me.clip.placeholderapi.");
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            var type = findLoadedClass(name);
            if (type == null) {
                if (sharedClass(name)) type = getParent().loadClass(name);
                else if (privateClass(name)) type = findClass(name);
                else {
                    try {
                        type = findClass(name);
                    } catch (ClassNotFoundException absent) {
                        type = getParent().loadClass(name);
                    }
                }
            }
            if (resolve) resolveClass(type);
            return type;
        }
    }

    @Override
    public URL getResource(String name) {
        var own = findResource(name);
        return own == null ? getParent().getResource(name) : own;
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        Set<URL> resources = new LinkedHashSet<>(Collections.list(findResources(name)));
        if (!name.startsWith("META-INF/services/") && !name.startsWith("ac/cult/")) {
            resources.addAll(Collections.list(getParent().getResources(name)));
        }
        return Collections.enumeration(resources);
    }
}
