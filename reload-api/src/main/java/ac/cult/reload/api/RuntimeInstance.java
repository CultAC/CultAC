package ac.cult.reload.api;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface RuntimeInstance {
    /** Shown to a player whose connection cannot be attached; it names neither the anticheat nor the cause. */
    String CONNECTION_REFUSED = "Unable to connect. Please try again.";

    void load() throws Exception;

    void start() throws Exception;

    CompletionStage<Void> close();

    /**
     * A newer generation has become current. This one keeps serving its pinned connections until it is closed, but
     * must hand over or stop any process-wide external resource the new generation also claims. Called once, before
     * the generation drains. It runs on a lifecycle worker with the generation's context and should return promptly.
     * The host bounds its wait and keeps the runtime and class loader alive until the call actually returns.
     */
    default void superseded() {}

    void attach(Object connection, Object nativePlayer) throws Exception;

    CompletionStage<Void> disconnect(Object connection);

    /**
     * Gives a live connection to another generation, on the connection's own thread at a protocol boundary. The next
     * owner adopts it within the same task.
     *
     * @return client state as JDK values and opaque native handles (no engine classes), or null to keep the connection
     */
    default java.util.Map<String, Object> release(Object connection) throws Exception {
        return null;
    }

    /** Attaches a connection another generation released, continuing its session with the released state. */
    default void adopt(Object connection, Object nativePlayer, java.util.Map<String, Object> state) throws Exception {
        attach(connection, nativePlayer);
    }

    Object api();

    default CompletionStage<Void> execute(Object sender, String command) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    default CompletionStage<java.util.List<String>> suggest(Object sender, String command) {
        return java.util.concurrent.CompletableFuture.completedFuture(java.util.List.of());
    }

    java.util.List<String> DEFAULT_COMMAND_ROOTS = java.util.List.of("cult", "cultac", "grim", "grimac");

    default java.util.List<String> commandRoots() {
        return DEFAULT_COMMAND_ROOTS;
    }

    default java.util.Map<String, byte[]> configurationFiles() throws Exception {
        return java.util.Map.of();
    }

    default Object prepareReload(Object configuration) throws Exception {
        return configuration;
    }

    default Object currentConfiguration() {
        return null;
    }

    default boolean applyReload(Object configuration) throws Exception {
        return true;
    }

    default java.util.List<String> placeholders() {
        return java.util.List.of();
    }

    default String placeholder(UUID player, String parameter) {
        return null;
    }

    default int playerCount() {
        return 0;
    }

    /** Whether this runtime acts on nothing it finds, so a connection it cannot attach still joins. */
    default boolean isObserveOnly() {
        return false;
    }

    /** Whether a connection this runtime cannot attach still joins, uninspected. */
    default boolean failsOpen() {
        return isObserveOnly();
    }
}
