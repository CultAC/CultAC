package ac.cult.placement;

import java.io.*;
import java.lang.invoke.MethodHandle;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.*;

/** JDK-only loader that releases archive indexes between requests and can reopen on later class/resource demand. */
public final class ArchiveLoader extends SecureClassLoader implements AutoCloseable {
    private final ClassLoader apiLoader;
    private final Path[] roots;
    private final Set<String> sharedPackages;
    private final boolean narrowed;
    private final String bridgePrefix;
    private final MethodHandle define;
    private final Map<Path, JarFile> files = new HashMap<>();

    public ArchiveLoader(URL[] urls, ClassLoader parent) throws Exception {
        this(urls, parent, Set.of(), false);
    }
    /**
     * {@code sharedPackages} resolve from the host (see {@link SharedLibraries}); {@code narrowed}
     * defines the client-model narrowing (see {@link ModelNarrowing}).
     */
    public ArchiveLoader(URL[] urls, ClassLoader parent, Set<String> sharedPackages, boolean narrowed)
            throws Exception {
        this(urls, parent, sharedPackages, narrowed, "");
    }

    ArchiveLoader(URL[] urls, ClassLoader parent, Set<String> sharedPackages, boolean narrowed, String bridgePrefix)
            throws Exception {
        super("placement-engine", ClassLoader.getPlatformClassLoader());
        apiLoader = parent;
        this.sharedPackages = Set.copyOf(sharedPackages);
        this.narrowed = narrowed;
        this.bridgePrefix = bridgePrefix;
        roots = new Path[urls.length];
        for (int i = 0; i < urls.length; i++) roots[i] = Path.of(urls[i].toURI());
        define = ModelDefinitions.open(
                getClass(), roots[0].toAbsolutePath().getParent().resolve("cult-support"));
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith("ac.cult.placement.api.")) return apiLoader.loadClass(name);
        int dot = name.lastIndexOf('.');
        if (dot > 0 && sharedPackages.contains(name.substring(0, dot))) return apiLoader.loadClass(name);
        if (name.startsWith(String.join(".", "org", "slf4j") + ".") || name.startsWith("org.apache.logging.")) {
            try {
                return apiLoader.loadClass(name);
            } catch (ClassNotFoundException standaloneHost) {
                /* Use the acquired logger when no host provides it. */
            }
        }
        return super.loadClass(name, resolve);
    }

    @Override
    public URL getResource(String name) {
        if (name.startsWith("log4j2")) return apiLoader.getResource(name);
        return super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        if (name.startsWith("log4j2")) return apiLoader.getResources(name);
        return super.getResources(name);
    }

    private synchronized JarFile zip(Path p) throws IOException {
        JarFile z = files.get(p);
        if (z == null) {
            z = new JarFile(p.toFile(), false, ZipFile.OPEN_READ, Runtime.version());
            files.put(p, z);
        }
        return z;
    }

    private synchronized byte[] bytes(Path root, String name) throws IOException {
        if (Files.isDirectory(root)) {
            Path p = root.resolve(name);
            return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
        }
        JarFile z = zip(root);
        ZipEntry e = z.getJarEntry(name);
        if (e == null) return null;
        try (InputStream in = z.getInputStream(e)) {
            return in.readAllBytes();
        }
    }

    private synchronized boolean has(Path root, String name) throws IOException {
        return Files.isDirectory(root)
                ? Files.isRegularFile(root.resolve(name))
                : zip(root).getJarEntry(name) != null;
    }

    private static String attribute(Attributes local, Attributes main, Attributes.Name name) {
        String v = local == null ? null : local.getValue(name);
        return v != null ? v : main == null ? null : main.getValue(name);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String file = name.replace('.', '/') + ".class";
        if (name.startsWith("ac.cult.vanilla.interaction.") || name.equals("ac.cult.placement.runtime.RequestTags"))
            file = bridgePrefix + file;
        for (Path root : roots)
            try {
                byte[] b = bytes(root, file);
                if (b == null) continue;
                int dot = name.lastIndexOf('.');
                if (dot > 0) {
                    String pack = name.substring(0, dot);
                    Manifest manifest =
                            Files.isDirectory(root) ? null : zip(root).getManifest();
                    Attributes main = manifest == null ? null : manifest.getMainAttributes(),
                            attrs = manifest == null ? null : manifest.getAttributes(pack.replace('.', '/') + "/");
                    URL seal = "true".equalsIgnoreCase(attribute(attrs, main, Attributes.Name.SEALED))
                            ? root.toUri().toURL()
                            : null;
                    Package existing = getDefinedPackage(pack);
                    if (existing == null)
                        try {
                            definePackage(
                                    pack,
                                    attribute(attrs, main, Attributes.Name.SPECIFICATION_TITLE),
                                    attribute(attrs, main, Attributes.Name.SPECIFICATION_VERSION),
                                    attribute(attrs, main, Attributes.Name.SPECIFICATION_VENDOR),
                                    attribute(attrs, main, Attributes.Name.IMPLEMENTATION_TITLE),
                                    attribute(attrs, main, Attributes.Name.IMPLEMENTATION_VERSION),
                                    attribute(attrs, main, Attributes.Name.IMPLEMENTATION_VENDOR),
                                    seal);
                        } catch (IllegalArgumentException race) {
                        }
                    else if (existing.isSealed()
                                    && !existing.isSealed(root.toUri().toURL())
                            || !existing.isSealed() && seal != null)
                        throw new SecurityException("Package sealing violation: " + pack);
                }
                b = VanillaClassTransform.apply(name, b, narrowed);
                try {
                    // Calling defineClass directly in a Paper-loaded class routes the
                    // bytes through Paper's host-NMS reflection remapper. These bytes
                    // belong exclusively to the verified isolated model.
                    return (Class<?>) define.invokeExact(
                            (SecureClassLoader) this,
                            name,
                            b,
                            0,
                            b.length,
                            new CodeSource(root.toUri().toURL(), (java.security.cert.Certificate[]) null));
                } catch (RuntimeException | Error failure) {
                    throw failure;
                } catch (Throwable failure) {
                    throw new ClassNotFoundException(name, failure);
                }
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        throw new ClassNotFoundException(name);
    }

    private URL resource(Path root, String name) throws MalformedURLException {
        if (Files.isDirectory(root)) return root.resolve(name).toUri().toURL();
        return new URL(null, "jar:" + root.toUri() + "!/" + name, new URLStreamHandler() {
            protected URLConnection openConnection(URL u) {
                return new URLConnection(u) {
                    public void connect() {}

                    public InputStream getInputStream() throws IOException {
                        String path = u.getFile();
                        int split = path.indexOf("!/");
                        if (split < 0) throw new IOException("Invalid archive resource: " + u);
                        String actual = path.substring(split + 2);
                        byte[] b = bytes(root, actual);
                        if (b == null) throw new FileNotFoundException(actual);
                        return new ByteArrayInputStream(b);
                    }
                };
            }
        });
    }

    @Override
    protected URL findResource(String name) {
        for (Path root : roots)
            try {
                if (has(root, name)) return resource(root, name);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        return null;
    }

    @Override
    protected Enumeration<URL> findResources(String name) throws IOException {
        List<URL> out = new ArrayList<>();
        for (Path root : roots) if (has(root, name)) out.add(resource(root, name));
        return Collections.enumeration(out);
    }

    public synchronized void releaseArchiveIndexes() throws IOException {
        for (ZipFile z : files.values()) z.close();
        files.clear();
    }

    @Override
    public void close() throws IOException {
        releaseArchiveIndexes();
    }
}
