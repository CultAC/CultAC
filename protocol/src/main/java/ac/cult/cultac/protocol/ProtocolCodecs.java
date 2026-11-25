package ac.cult.cultac.protocol;

import ac.cult.cultac.codec.PrivateCodecService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Directly linked, relocated value codecs shared by the platform. */
public final class ProtocolCodecs {
    private static volatile WireValueDecoder runtime;

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
        return current;
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
        if (current != null) current.close();
    }

    private static WireValueDecoder open() throws Exception {
        // Via's Config keeps a private SnakeYAML instance in a ThreadLocal. Initialize
        // it on an owned thread so no server/packet thread retains a retired runtime.
        var result = new CompletableFuture<WireValueDecoder>();
        var initializer = new Thread(
                () -> {
                    try {
                        result.complete(new PrivateCodecService());
                    } catch (Throwable failure) {
                        result.completeExceptionally(failure);
                    }
                },
                "cult-codec-initialize");
        initializer.setDaemon(true);
        initializer.start();
        boolean interrupted = false;
        try {
            while (initializer.isAlive()) {
                try {
                    initializer.join();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            return result.join();
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
