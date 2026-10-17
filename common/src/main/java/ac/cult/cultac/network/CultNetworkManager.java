package ac.cult.cultac.network;

import ac.cult.cultac.network.logging.JavaPacketLogger;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.ProtocolRuntime;
import ac.cult.cultac.utils.anticheat.LogUtil;
import io.netty.channel.Channel;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class CultNetworkManager {
    private final PacketConnections connections = new PacketConnections();
    private PacketDispatcher dispatcher;
    private Runnable installChannels;
    private Runnable startPlatform;
    private java.util.function.Supplier<CompletionStage<Void>> removeChannels;
    private java.util.function.Function<Channel, PacketOwner> ownerResolver = channel -> null;

    private boolean loaded;
    private boolean started;
    private CompletableFuture<Void> stopped;
    private volatile JavaPacketLogger packetLogger;

    public JavaPacketLogger getJavaPacketLogger() {
        return packetLogger;
    }

    public synchronized void initializePacketLogger(
            java.nio.file.Path directory, String platform, String version, java.util.function.Consumer<String> report) {
        if (packetLogger != null) return;
        var logger = new JavaPacketLogger(directory, platform, version, report);
        packetLogger = logger;
        for (var connection : connections.snapshot()) {
            connection.packetObserver(logger.observer(connection));
            connection.execute(connection::notifyPacketLogIdentity);
        }
    }

    public PacketDispatcher dispatcher() {
        return java.util.Objects.requireNonNull(dispatcher, "Configure transport before use");
    }

    public synchronized void configureTransport(
            ProtocolRuntime runtime, Runnable install, java.util.function.Supplier<CompletionStage<Void>> remove) {
        configureTransport(runtime, install, () -> {}, remove);
    }

    public synchronized void configureTransport(
            ProtocolRuntime runtime,
            Runnable install,
            Runnable start,
            java.util.function.Supplier<CompletionStage<Void>> remove) {
        if (dispatcher != null) throw new IllegalStateException("Transport already configured");
        dispatcher = new PacketDispatcher(runtime);
        installChannels = java.util.Objects.requireNonNull(install);
        startPlatform = java.util.Objects.requireNonNull(start);
        removeChannels = java.util.Objects.requireNonNull(remove);
    }

    public CultConnection createConnection(PlatformConnection platform, Channel channel) {
        var connection = new CultConnection(
                java.util.Objects.requireNonNull(platform), channel, dispatcher(), ch -> ownerResolver.apply(ch));
        var logger = packetLogger;
        if (logger != null) connection.packetObserver(logger.observer(connection));
        connections.attach(connection);
        return connection;
    }

    /**
     * Hooks new connections into the packet interceptor. Runs at plugin load so the
     * hook is in place before anything else snapshots the server's connection
     * initializer (Geyser captures it at enable time for its local channels).
     */
    public synchronized void load() {
        if (stopped != null && !stopped.isDone())
            throw new IllegalStateException("Network shutdown is still in progress");
        if (loaded) {
            return;
        }
        stopped = null;
        java.util.Objects.requireNonNull(installChannels, "Configure transport before load")
                .run();
        loaded = true;
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        load();
        startPlatform.run();
        started = true;
    }

    /** Read-only snapshot; null means shutdown has not started. Does not initiate or retry shutdown. */
    public synchronized CompletionStage<Void> shutdownCompletion() {
        return stopped == null ? null : stopped.minimalCompletionStage();
    }

    public CompletionStage<Void> stop() {
        CompletableFuture<Void> completion;
        synchronized (this) {
            if (stopped != null && !stopped.isCompletedExceptionally()) return stopped.minimalCompletionStage();
            started = false;
            completion = stopped = new CompletableFuture<>();
        }
        try {
            removeChannels
                    .get()
                    .thenCompose(ignored -> connections.disconnectRemainingUsers())
                    // Writer draining must not block a connection's packet owner.
                    .thenRunAsync(this::finishStop)
                    .whenComplete((ignored, failure) -> {
                        if (failure == null) completion.complete(null);
                        else completion.completeExceptionally(failure);
                    });
        } catch (RuntimeException | Error failure) {
            completion.completeExceptionally(failure);
        }
        return completion.minimalCompletionStage();
    }

    private synchronized void finishStop() {
        var logger = packetLogger;
        packetLogger = null;
        if (logger != null) logger.close();
        connections.clearUsers();
        dispatcher.clear();
        loaded = false;
    }

    public void lifecycleHooks(UserLifecycleHooks hooks) {
        connections.hooks(hooks);
    }

    public CultConnection connection(Channel channel) {
        return connections.get(channel);
    }

    public List<CultConnection> connections() {
        return connections.snapshot();
    }

    public CompletionStage<Void> disconnect(Channel channel) {
        var connection = connections.get(channel);
        return connection == null ? CompletableFuture.completedFuture(null) : connections.disconnect(connection);
    }

    /** Ends this engine's session without ending the player's; call on its packet owner after removing handlers. */
    public void release(Channel channel) {
        var connection = connections.get(channel);
        if (connection != null) connections.release(connection);
    }

    public CompletionStage<Void> disconnect(User user) {
        return connections.disconnect(user.getCultConnection());
    }

    public User getUser(PlatformPlayer player) {
        return connections.getUser(player);
    }

    public User getUser(UUID uuid) {
        return connections.getUser(uuid);
    }

    /** Platform adapters must supply the player instance as well as its account identity. */
    public User getUser(UUID uuid, Object nativePlayer) {
        return connections.getUser(uuid, nativePlayer);
    }

    public void setPacketOwnerResolver(java.util.function.Function<Channel, PacketOwner> resolver) {
        ownerResolver = java.util.Objects.requireNonNull(resolver);
    }

    public void playerJoined(Channel channel) {
        var connection = connections.get(channel);
        if (connection != null) connections.playerJoined(connection);
    }

    public static void runDeferredPacketTask(String phase, Runnable runnable) {
        try {
            runnable.run();
        } catch (Throwable throwable) {
            reportFailure("Deferred packet task in " + phase, throwable);
        }
    }

    /** Cult failures stay out of the host's exception handler, which may disconnect players. */
    public static void reportFailure(String operation, Throwable failure) {
        try {
            LogUtil.error("CultAC failure in " + operation + "; keeping the connection open", failure);
        } catch (Throwable loggingFailure) {
            // Logging during startup/shutdown must not turn a recovered failure into a kick.
            try {
                failure.printStackTrace();
            } catch (Throwable ignored) {
            }
        }
    }

    public Object getChannel(UUID uuid) {
        User user = getUser(uuid);
        return user == null ? null : user.getChannel();
    }
}
