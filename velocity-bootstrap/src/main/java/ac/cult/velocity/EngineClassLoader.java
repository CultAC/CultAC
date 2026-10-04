package ac.cult.velocity;

import java.net.URL;
import java.net.URLClassLoader;

/** The engine owns its model and libraries; proxy API values retain their proxy identity. */
final class EngineClassLoader extends URLClassLoader {
    static {
        registerAsParallelCapable();
    }

    EngineClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Override
    public URL getResource(String name) {
        // The vanilla client ships a standalone application logging configuration.
        if (name.startsWith("log4j2")) return getParent().getResource(name);
        return super.getResource(name);
    }

    @Override
    public java.util.Enumeration<URL> getResources(String name) throws java.io.IOException {
        if (name.startsWith("log4j2")) return getParent().getResources(name);
        return super.getResources(name);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> type = findLoadedClass(name);
            if (type == null) {
                if (shared(name)) type = super.loadClass(name, false);
                else {
                    try {
                        type = findClass(name);
                    } catch (ClassNotFoundException missing) {
                        // A missing model class must never resolve through another plugin's NMS.
                        if (name.startsWith("net.minecraft.") || name.startsWith("ac.cult.")) throw missing;
                        type = super.loadClass(name, false);
                    }
                }
            }
            if (resolve) resolveClass(type);
            return type;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (!ac.cult.runtime.RegistryReadTransform.applies(name)) return super.findClass(name);
        var resource = findResource(name.replace('.', '/') + ".class");
        if (resource == null) throw new ClassNotFoundException(name);
        try {
            var source = resource.openConnection();
            byte[] original;
            try (var input = source.getInputStream()) {
                original = input.readAllBytes();
            }
            byte[] bytes = ac.cult.runtime.RegistryReadTransform.apply(
                    name, original, "ac/cult/cultac/vanilla/VanillaContext");
            // Reading the complete original entry verifies its signature. Retain the
            // jar's code source so transformed classes share their package's signers.
            var codeSource = source instanceof java.net.JarURLConnection jar
                    ? new java.security.CodeSource(
                            jar.getJarFileURL(), jar.getJarEntry().getCodeSigners())
                    : new java.security.CodeSource(resource, (java.security.CodeSigner[]) null);
            return defineClass(name, bytes, 0, bytes.length, codeSource);
        } catch (java.io.IOException failure) {
            throw new ClassNotFoundException(name, failure);
        }
    }

    private static boolean shared(String name) {
        return name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("jdk.")
                || name.startsWith("sun.")
                || name.startsWith("com.velocitypowered.")
                || name.startsWith("io.netty.")
                || name.startsWith("net.kyori.")
                || name.startsWith("com.mojang.brigadier.")
                || name.startsWith("org.slf4j.")
                || name.startsWith("org.apache.logging.")
                || name.startsWith("com.google.inject.")
                || name.startsWith("org.jetbrains.annotations.");
    }
}
