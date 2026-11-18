package ac.cult.cultac.platform.velocity;

import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.utils.anticheat.LogUtil;
import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyReloadEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import java.lang.reflect.Method;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/**
 * Forwards a backend's compressed frames to its client without compressing them again, when switched on with
 * {@value #ENABLED_PROPERTY}. Each Java client gets a {@link CompressedForwarder}; each backend it is connected to on
 * the same event loop gets a {@link CompressedKeep} and {@link CompressedMark} pair publishing into it.
 */
final class VelocityCompressedForward {
    static final String ENABLED_PROPERTY = "cult.velocity.forwardCompressed";
    static final String GUARD_PROPERTY = "cult.velocity.forwardCompressedGuard";
    static final String THRESHOLD_STATE = "compressed-forward-threshold";
    static final String COMPRESSION_DECODER = "compression-decoder";

    private final ProxyServer proxy;
    private final Object plugin;
    private final Function<Player, Channel> channels;
    private final boolean guard;
    private final boolean small;
    private final boolean smallHeap;
    private final int startThreshold;
    private volatile boolean thresholdChanged;
    private volatile Method inFlightMethod;
    private final EventHandler<ServerConnectedEvent> connected = this::connected;
    private final EventHandler<ServerPostConnectEvent> postConnected = this::postConnected;
    private final EventHandler<ProxyReloadEvent> reloaded = event -> clientThreshold();
    private boolean listening;

    VelocityCompressedForward(
            ProxyServer proxy,
            Object plugin,
            Function<Player, Channel> channels,
            boolean guard,
            boolean small,
            boolean smallHeap,
            int startThreshold) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.channels = channels;
        this.guard = guard;
        this.small = small;
        this.smallHeap = smallHeap;
        this.startThreshold = startThreshold;
    }

    /** Null unless {@value #ENABLED_PROPERTY} is true; nothing is registered or installed then. */
    static @Nullable VelocityCompressedForward fromSystemProperties(
            ProxyServer proxy, Object plugin, Function<Player, Channel> channels) {
        if (!Boolean.getBoolean(ENABLED_PROPERTY)) return null;
        boolean guard = !"false".equalsIgnoreCase(System.getProperty(GUARD_PROPERTY, "true"));
        Boolean javaCipher = javaCipher(proxy.getClass().getClassLoader());
        if (javaCipher == null)
            LogUtil.warn("Cannot tell which cipher Velocity uses; frames below the compression threshold are framed"
                    + " by Velocity");
        return new VelocityCompressedForward(
                proxy,
                plugin,
                channels,
                guard,
                javaCipher != null,
                Boolean.TRUE.equals(javaCipher),
                proxy.getConfiguration().getCompressionThreshold());
    }

    /** Whether Velocity encrypts with its Java cipher, which needs heap buffers; null when it cannot be read. */
    static @Nullable Boolean javaCipher(ClassLoader loader) {
        try {
            Object cipher = Class.forName("com.velocitypowered.natives.util.Natives", false, loader)
                    .getField("cipher")
                    .get(null);
            Object javaFactory = Class.forName(
                            "com.velocitypowered.natives.encryption.JavaVelocityCipher", false, loader)
                    .getField("FACTORY")
                    .get(null);
            if (!(cipher instanceof Supplier<?> factory)) return null;
            return factory.get() == javaFactory;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            return null;
        }
    }

    synchronized void listen() {
        if (listening) return;
        listening = true;
        proxy.getEventManager().register(plugin, ServerConnectedEvent.class, connected);
        proxy.getEventManager().register(plugin, ServerPostConnectEvent.class, postConnected);
        proxy.getEventManager().register(plugin, ProxyReloadEvent.class, reloaded);
    }

    String describe() {
        return "Forwarding backends' compressed frames to clients (Adler-32 guard " + (guard ? "on" : "off") + ")";
    }

    synchronized void close() {
        if (!listening) return;
        listening = false;
        proxy.getEventManager().unregister(plugin, connected);
        proxy.getEventManager().unregister(plugin, postConnected);
        proxy.getEventManager().unregister(plugin, reloaded);
    }

    /**
     * The threshold Velocity sent clients that authenticate now, or -1 once it has changed since start: a client that
     * authenticated before a change and attaches after it would otherwise be framed at the wrong threshold.
     */
    int clientThreshold() {
        int now = proxy.getConfiguration().getCompressionThreshold();
        if (now != startThreshold && !thresholdChanged) {
            thresholdChanged = true;
            LogUtil.warn("The compression threshold changed; clients that connect from now on get frames compressed"
                    + " by Velocity until the proxy restarts");
        }
        return thresholdChanged ? -1 : now;
    }

    /**
     * Installs the forwarder on a Java client's channel, on that channel's loop, after Cult's encoder.
     *
     * @param adopted the threshold an earlier runtime framed this client with, or null for a new client
     */
    void installClient(
            CultConnection connection, VelocityConnectionAdapter adapter, Player player, @Nullable Integer adopted) {
        Channel channel = connection.channel();
        if (connection.owner() != channel.eventLoop()) return;
        int threshold = adopted != null ? adopted : clientThreshold();
        if (threshold < 0) return;
        var forwarder = install(channel.pipeline(), threshold, guard, small, smallHeap);
        if (forwarder == null) return;
        adapter.compressedForwarder(forwarder);
        schedule(channel, () -> player.getCurrentServer().orElse(null));
    }

    static @Nullable CompressedForwarder install(
            ChannelPipeline pipeline, int threshold, boolean guard, boolean small, boolean smallHeap) {
        if (pipeline.get(CompressedForwarder.COMPRESSION_ENCODER) == null
                || pipeline.get(CompressedForwarder.NAME) != null) return null;
        var forwarder = new CompressedForwarder(threshold, guard, small, smallHeap);
        pipeline.addAfter(CompressedForwarder.COMPRESSION_ENCODER, CompressedForwarder.NAME, forwarder);
        if (!CompressedForwarder.adjacent(pipeline)) {
            pipeline.remove(forwarder);
            forwarder.disable("adjacency");
        }
        return forwarder;
    }

    /** Removes the forwarder and its backend link; runs on the client's loop or its packet owner. */
    static void uninstall(Channel client) {
        var pipeline = client.pipeline();
        if (!(pipeline.get(CompressedForwarder.NAME) instanceof CompressedForwarder forwarder)) return;
        forwarder.unlink();
        pipeline.remove(forwarder);
    }

    private void connected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        RegisteredServer server = event.getServer();
        Channel client = client(player);
        if (client == null) return;
        // Velocity resumes the backend's reads with a task it queues on this loop after every handler returns.
        schedule(client, () -> {
            Object inFlight = inFlight(player);
            return inFlight instanceof ServerConnection connection && connection.getServer() == server
                    ? inFlight
                    : null;
        });
    }

    private void postConnected(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        Channel client = client(player);
        if (client != null) schedule(client, () -> player.getCurrentServer().orElse(null));
    }

    private @Nullable Channel client(Player player) {
        try {
            return channels.apply(player);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /** Links the client's forwarder, on the client's loop, to the backend {@code server} names when the task runs. */
    private static void schedule(Channel client, Supplier<Object> server) {
        try {
            client.eventLoop().execute(() -> {
                if (!(client.pipeline().get(CompressedForwarder.NAME) instanceof CompressedForwarder forwarder)) return;
                Channel backend;
                try {
                    Object connection = server.get();
                    backend = connection == null ? null : VelocityBackendChannels.channel(connection);
                } catch (RuntimeException failure) {
                    forwarder.linkStatus("lookup-failed");
                    return;
                }
                if (backend != null) link(forwarder, client, backend);
            });
        } catch (RejectedExecutionException closed) {
            // The client's loop is shut down, and with it the channel.
        }
    }

    private @Nullable Object inFlight(Player player) {
        try {
            Method method = inFlightMethod;
            if (method == null) inFlightMethod = method = player.getClass().getMethod("getConnectionInFlight");
            return method.invoke(player);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    /** Links {@code forwarder} to {@code backend}; runs on the client's loop. */
    static void link(CompressedForwarder forwarder, Channel client, Channel backend) {
        if (forwarder.disabled || !client.isActive()) return;
        if (backend.eventLoop() != client.eventLoop()) {
            forwarder.unlink();
            forwarder.linkStatus("loop");
            return;
        }
        if (!backend.isActive()) {
            forwarder.linkStatus("backend-inactive");
            return;
        }
        place(forwarder, client.pipeline(), backend);
    }

    /** Places the keep and mark pair around the backend's compression decoder; both channels share one loop. */
    static void place(CompressedForwarder forwarder, ChannelPipeline client, Channel backend) {
        if (forwarder.disabled || forwarder.linkedTo(backend)) return;
        forwarder.unlink();
        var pipeline = backend.pipeline();
        if (pipeline.get(COMPRESSION_DECODER) == null) {
            forwarder.linkStatus("backend-uncompressed");
            return;
        }
        if (!CompressedForwarder.adjacent(client)) {
            forwarder.disable("adjacency");
            return;
        }
        if (pipeline.get(CompressedKeep.NAME) != null || pipeline.get(CompressedMark.NAME) != null) {
            forwarder.linkStatus("backend-occupied");
            return;
        }
        var keep = new CompressedKeep(forwarder);
        var mark = new CompressedMark(keep, forwarder);
        pipeline.addBefore(COMPRESSION_DECODER, CompressedKeep.NAME, keep);
        pipeline.addAfter(COMPRESSION_DECODER, CompressedMark.NAME, mark);
        var names = pipeline.names();
        int at = names.indexOf(COMPRESSION_DECODER);
        if (at < 1
                || at + 1 >= names.size()
                || !CompressedKeep.NAME.equals(names.get(at - 1))
                || !CompressedMark.NAME.equals(names.get(at + 1))) {
            pipeline.remove(keep);
            pipeline.remove(mark);
            forwarder.linkStatus("backend-adjacency");
            return;
        }
        forwarder.link(backend, keep, mark);
    }
}
