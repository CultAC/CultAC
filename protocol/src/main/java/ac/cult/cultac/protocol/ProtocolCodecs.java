package ac.cult.cultac.protocol;

import ac.cult.cultac.codec.PrivateCodecService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Directly linked, relocated value codecs shared by the platform. */
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
                        throw new ProtocolResolutionException("Cannot initialize CultAC value codecs", failure);
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
            current.decoder().close();
        } finally {
            deleteDirectory(current.directory());
        }
    }

    private static Runtime open() throws Exception {
        // ViaBackwards' configuration API needs a data directory; no code is extracted.
        Path directory = Files.createTempDirectory("cult-value-codecs-");
        try {
            return new Runtime(directory, new PrivateCodecService(directory));
        } catch (Exception | Error failure) {
            deleteDirectory(directory);
            throw failure;
        }
    }

    private static void deleteDirectory(Path directory) {
        try (var files = Files.walk(directory)) {
            for (var path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException("Cannot close CultAC value codecs", failure);
        }
    }

    private record Runtime(Path directory, WireValueDecoder decoder) {}
}
