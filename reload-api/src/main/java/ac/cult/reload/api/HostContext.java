package ac.cult.reload.api;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Opaque native handles are host-owned; engine classes must not cross this boundary. */
public interface HostContext {
    String generationId();

    String platform();

    String build();

    Path directory();

    Object plugin();

    Object server();

    Object logger();

    ResourceScope resources();

    boolean current();

    boolean owns(Object session);

    boolean pin(Object session);

    void release(Object session, CompletionStage<Void> cleanup);

    /** The pinned generation, or null for an unmanaged native connection. */
    String ownerOf(Object connection);

    /** Called by the owning engine at the reconfiguration acknowledgement, on its packet thread. */
    default boolean handoff(Object connection, Object nativePlayer) throws Exception {
        return false;
    }

    void bindUser(Object connection, UUID uuid, Object nativePlayer, Object user);

    void unbindUser(Object connection);

    Object shared(String key, Supplier<Object> create);

    Object publicApi();

    Object wrap(Object value);

    Object wrap(Object value, Object user);

    CompletionStage<String> update(String filename);

    List<String> status();

    /**
     * Installs signed build {@code number} as {@code /cult update <number>} does (state file, log, webhook), naming
     * {@code requestedBy} in the log and webhook lines; a non-null {@code branch} requires the manifest to be signed for
     * it. Completes with the result line. Loaders built before this method existed lack it, so engines look it up
     * before calling it.
     */
    default CompletionStage<String> installBuild(long number, String branch, String requestedBy) {
        return CompletableFuture.completedFuture("update refused: this server does not install builds");
    }

    /** The newest build number of {@code branch}: one GitHub API request. */
    default CompletionStage<Long> latestBuild(String branch) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("this server does not list builds"));
    }
}
