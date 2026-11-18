package ac.cult.cultac.platform.velocity;

import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.utils.anticheat.LogUtil;
import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
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
    static final String COMPRESSION_DECODER = "compression-decoder";
    static final String VELOCITY_ENCODER =
            "com.velocitypowered.proxy.protocol.netty.MinecraftCompressorAndLengthEncoder";

    private final ProxyServer proxy;
    private final Object plugin;
    private final Function<Player, Channel> channels;
    private final boolean guard;
    private final boolean small;
    private final boolean heap;
    private volatile boolean encoderUnsupported;
    private volatile Method inFlightMethod;
    private static volatile @Nullable MethodHandle thresholdGetter;
    private final EventHandler<ServerConnectedEvent> connected = this::connected;
    private final EventHandler<ServerPostConnectEvent> postConnected = this::postConnected;
    private boolean listening;

    VelocityCompressedForward(
            ProxyServer proxy,
            Object plugin,
            Function<Player, Channel> channels,
            boolean guard,
            boolean small,
            boolean heap) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.channels = channels;
        this.guard = guard;
        this.small = small;
        this.heap = heap;
    }

    /** Null unless {@value #ENABLED_PROPERTY} is true; nothing is registered or installed then. */
    static @Nullable VelocityCompressedForward fromSystemProperties(
            ProxyServer proxy, Object plugin, Function<Player, Channel> channels) {
        if (!Boolean.getBoolean(ENABLED_PROPERTY)) return null;
        if (!CompressedForwarder.supported()) {
            LogUtil.warn("Cannot read the handler order of this netty; compressed frames are not forwarded");
            return null;
        }
        boolean guard = !"false".equalsIgnoreCase(System.getProperty(GUARD_PROPERTY, "true"));
        if (!guard) LogUtil.warn("The content check on forwarded frames is off; run this for measurements only");
        Boolean javaCipher = javaCipher(proxy.getClass().getClassLoader());
        if (javaCipher == null)
            LogUtil.warn("Cannot tell which cipher Velocity uses; frames below the compression threshold are framed"
                    + " by Velocity");
        return new VelocityCompressedForward(
                proxy, plugin, channels, guard, javaCipher != null, Boolean.TRUE.equals(javaCipher));
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
    }

    String describe() {
        return "Forwarding backends' compressed frames to clients (content check " + (guard ? "on" : "off") + ")";
    }

    synchronized void close() {
        if (!listening) return;
        listening = false;
        proxy.getEventManager().unregister(plugin, connected);
        proxy.getEventManager().unregister(plugin, postConnected);
    }

    /** Installs the forwarder on a Java client's channel, on that channel's loop, after Cult's encoder. */
    void installClient(CultConnection connection, VelocityConnectionAdapter adapter, Player player) {
        Channel channel = connection.channel();
        if (connection.owner() != channel.eventLoop()) return;
        var pipeline = channel.pipeline();
        var forwarder = install(pipeline, VELOCITY_ENCODER, guard, small, heap);
        if (forwarder == null) {
            if (!encoderUnsupported
                    && pipeline.get(CompressedForwarder.COMPRESSION_ENCODER) != null
                    && pipeline.get(CompressedForwarder.NAME) == null) {
                encoderUnsupported = true;
                LogUtil.warn("Cannot read a client's compression threshold; its frames are compressed by Velocity");
            }
            return;
        }
        adapter.compressedForwarder(forwarder);
        schedule(channel, () -> player.getCurrentServer().orElse(null));
    }

    /**
     * A getter of the client encoder's current threshold, bound to that encoder, when it is an instance of the class
     * named {@code encoderClass} with an {@code int threshold} field; null otherwise.
     */
    static @Nullable MethodHandle thresholdReader(ChannelPipeline pipeline, String encoderClass) {
        Object encoder = pipeline.get(CompressedForwarder.COMPRESSION_ENCODER);
        if (encoder == null || !encoder.getClass().getName().equals(encoderClass)) return null;
        try {
            Class<?> type = encoder.getClass();
            MethodHandle getter = thresholdGetter;
            if (getter == null || getter.type().parameterType(0) != type) {
                getter = MethodHandles.privateLookupIn(type, MethodHandles.lookup())
                        .findGetter(type, "threshold", int.class);
                thresholdGetter = getter;
            }
            return getter.bindTo(encoder);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    /** The forwarder for a client whose encoder is of the class named {@code encoderClass}, or null. */
    static @Nullable CompressedForwarder install(
            ChannelPipeline pipeline, String encoderClass, boolean guard, boolean small, boolean heap) {
        if (!CompressedForwarder.supported() || pipeline.get(CompressedForwarder.NAME) != null) return null;
        MethodHandle threshold = thresholdReader(pipeline, encoderClass);
        if (threshold == null) return null;
        var forwarder = new CompressedForwarder(threshold, guard, small, heap);
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
        try {
            pipeline.addBefore(COMPRESSION_DECODER, CompressedKeep.NAME, keep);
            pipeline.addAfter(COMPRESSION_DECODER, CompressedMark.NAME, mark);
        } catch (RuntimeException failure) {
            if (pipeline.context(keep) != null) pipeline.remove(keep);
            if (pipeline.context(mark) != null) pipeline.remove(mark);
            forwarder.linkStatus("backend-install-failed");
            return;
        }
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
