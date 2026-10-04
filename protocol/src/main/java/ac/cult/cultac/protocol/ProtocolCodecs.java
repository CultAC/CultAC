package ac.cult.cultac.protocol;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

/** One isolated, stateless Via library shared by the platform's mappings and value decoders. */
public final class ProtocolCodecs {
    private static volatile Runtime runtime;

    private ProtocolCodecs() {}

    public static WireValueDecoder decoder() {
        var current = runtime;
        if (current == null) {
            synchronized (ProtocolCodecs.class) {
                current = runtime;
                if (current == null) {
                    try {
                        runtime = current = open();
                    } catch (Exception failure) {
                        throw new ProtocolResolutionException("Cannot initialize Via codecs", failure);
                    }
                }
            }
        }
        return current.decoder();
    }

    public static Map<String, List<String>> projectTags(
            String registry,
            Map<String, List<String>> tags,
            ProtocolVersion source,
            ProtocolVersion client,
            ProtocolVersion target) {
        return decoder().tags(registry, tags, source, client, target);
    }

    public static synchronized void close() {
        var current = runtime;
        runtime = null;
        if (current == null) return;
        try {
            try {
                current.decoder().close();
            } finally {
                try {
                    current.loader().close();
                } finally {
                    try (var files = Files.walk(current.directory())) {
                        for (var path : files.sorted(java.util.Comparator.reverseOrder())
                                .toList()) Files.deleteIfExists(path);
                    }
                }
            }
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException("Cannot close Via codecs", failure);
        }
    }

    private static Runtime open() throws Exception {
        Path directory = Files.createTempDirectory("cult-protocol-codecs-");
        Path jar = directory.resolve("protocol-codecs.jar");
        CodecLoader loader = null;
        try {
            try (var input = ProtocolCodecs.class.getResourceAsStream("/runtime/protocol-codecs.jar")) {
                if (input == null) throw new IOException("Missing private packet codecs");
                Files.copy(input, jar);
            }
            loader = new CodecLoader(jar.toUri().toURL(), ProtocolCodecs.class.getClassLoader());
            var decoder = (WireValueDecoder) loader.loadClass("ac.cult.cultac.codec.PrivateCodecService")
                    .getConstructor(Path.class)
                    .newInstance(directory);
            return new Runtime(directory, loader, decoder);
        } catch (Exception | Error failure) {
            if (loader != null) loader.close();
            try (var files = Files.walk(directory)) {
                for (var path :
                        files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
            throw failure;
        }
    }

    private record Runtime(Path directory, CodecLoader loader, WireValueDecoder decoder) {}

    static final class CodecLoader extends URLClassLoader {
        static {
            registerAsParallelCapable();
        }

        CodecLoader(URL jar, ClassLoader parent) {
            super(new URL[] {jar}, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null) type = ownClass(name) ? findClass(name) : super.loadClass(name, false);
                if (resolve) resolveClass(type);
                return type;
            }
        }

        @Override
        public URL getResource(String name) {
            return ownResource(name) ? findResource(name) : super.getResource(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            return ownResource(name) ? findResources(name) : super.getResources(name);
        }

        private static boolean ownClass(String name) {
            return name.startsWith("com.viaversion.") || name.startsWith("ac.cult.cultac.codec.");
        }

        private static boolean ownResource(String name) {
            return name.startsWith("assets/viaversion/")
                    || name.startsWith("assets/viabackwards/")
                    || name.startsWith("com/viaversion/")
                    || name.startsWith("ac/cult/cultac/codec/");
        }
    }
}
