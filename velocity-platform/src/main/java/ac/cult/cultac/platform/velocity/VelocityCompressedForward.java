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
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/**
 * Forwards a backend's compressed frames to its client without compressing them again, when {@value
 * #ENABLED_PROPERTY} is true: a {@link CompressedForwarder} per Java client, a keep and mark pair on its backend.
 */
final class VelocityCompressedForward {
    static final String ENABLED_PROPERTY = "cult.velocity.forwardCompressed";
    static final String COMPRESSION_DECODER = "compression-decoder";
    static final String VELOCITY_ENCODER =
            "com.velocitypowered.proxy.protocol.netty.MinecraftCompressorAndLengthEncoder";
    static final String VELOCITY_DECODER = "com.velocitypowered.proxy.protocol.netty.MinecraftCompressDecoder";

    private final ProxyServer proxy;
    private final Object plugin;
    private final Function<Player, Channel> channels;
    private final boolean small;
    private final boolean heap;
    private final boolean consume;
    private final String decoderClass;
    private final AtomicBoolean[] warned = new AtomicBoolean[Refusal.values().length];
    private volatile Method inFlightMethod;
    private static volatile @Nullable MethodHandle thresholdGetter;
    private static volatile @Nullable MethodHandle limiterGetter;
    static volatile @Nullable MethodHandle encoderCompressorGetter;
    static volatile @Nullable MethodHandle decoderCompressorGetter;
    private static volatile @Nullable MethodHandle disposedGetter;
    private final EventHandler<ServerConnectedEvent> connected = this::connected;
    private final EventHandler<ServerPostConnectEvent> postConnected = this::postConnected;
    private boolean listening;

    VelocityCompressedForward(
            ProxyServer proxy,
            Object plugin,
            Function<Player, Channel> channels,
            boolean small,
            boolean heap,
            boolean consume,
            String decoderClass) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.channels = channels;
        this.small = small;
        this.heap = heap;
        this.consume = consume;
        this.decoderClass = decoderClass;
        for (int i = 0; i < warned.length; i++) warned[i] = new AtomicBoolean();
    }

    /** Null unless {@value #ENABLED_PROPERTY} is true; nothing is registered or installed then. */
    static @Nullable VelocityCompressedForward fromSystemProperties(
            ProxyServer proxy, Object plugin, Function<Player, Channel> channels) {
        if (!Boolean.getBoolean(ENABLED_PROPERTY)) return null;
        if (!CompressedForwarder.supported()) {
            LogUtil.warn("Cannot read the handler order of this netty; compressed frames are not forwarded");
            return null;
        }
        ClassLoader loader = proxy.getClass().getClassLoader();
        return create(proxy, plugin, channels, javaCipher(loader), javaCompressor(loader));
    }

    /** Null when {@code javaCompressor}, the compressor probe's result, is null. */
    static @Nullable VelocityCompressedForward create(
            ProxyServer proxy,
            Object plugin,
            Function<Player, Channel> channels,
            @Nullable Boolean javaCipher,
            @Nullable Boolean javaCompressor) {
        if (javaCompressor == null) {
            LogUtil.warn("Cannot tell which compressor Velocity uses; compressed frames are not forwarded");
            return null;
        }
        if (javaCipher == null)
            LogUtil.warn("Cannot tell which cipher Velocity uses; frames below the compression threshold are framed"
                    + " by Velocity");
        return new VelocityCompressedForward(
                proxy,
                plugin,
                channels,
                javaCipher != null,
                Boolean.TRUE.equals(javaCipher),
                javaCompressor,
                VELOCITY_DECODER);
    }

    /** Whether Velocity encrypts with its Java cipher, which needs heap buffers; null when it cannot be read. */
    static @Nullable Boolean javaCipher(ClassLoader loader) {
        return javaFactory(loader, "cipher", "com.velocitypowered.natives.encryption.JavaVelocityCipher");
    }

    /** Whether Velocity compresses with its Java compressor, which consumes its input; null when it cannot be read. */
    static @Nullable Boolean javaCompressor(ClassLoader loader) {
        return javaFactory(loader, "compress", "com.velocitypowered.natives.compression.JavaVelocityCompressor");
    }

    /** Whether the factory in Velocity's {@code Natives.<field>} is {@code javaClass.FACTORY}, or null. */
    private static @Nullable Boolean javaFactory(ClassLoader loader, String field, String javaClass) {
        try {
            Object loaded = Class.forName("com.velocitypowered.natives.util.Natives", false, loader)
                    .getField(field)
                    .get(null);
            Object javaFactory =
                    Class.forName(javaClass, false, loader).getField("FACTORY").get(null);
            if (!(loaded instanceof Supplier<?> factory)) return null;
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
        return "Forwarding backends' compressed frames to clients";
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
        var forwarder = install(pipeline, VELOCITY_ENCODER, decoderClass, small, heap, consume, this::refused);
        if (forwarder == null) return;
        adapter.compressedForwarder(forwarder);
        schedule(channel, decoderClass, () -> player.getCurrentServer().orElse(null));
    }

    /** Logs the first refusal of each kind, once across every loop. */
    void refused(Refusal refusal) {
        if (warned[refusal.ordinal()].compareAndSet(false, true)) LogUtil.warn(refusal.warning);
    }

    /** A getter bound to {@code encoder}'s {@code int threshold} when it is of the class {@code encoderClass}, or null. */
    static @Nullable MethodHandle thresholdReader(@Nullable Object encoder, String encoderClass) {
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

    /** A getter bound to {@code decoder}'s {@code packetLimiter} field, typed {@code ()Object}, or null. */
    static @Nullable MethodHandle limiterReader(Object decoder) {
        try {
            Class<?> type = decoder.getClass();
            MethodHandle getter = limiterGetter;
            if (getter == null || getter.type().parameterType(0) != type) {
                Class<?> field = type.getDeclaredField("packetLimiter").getType();
                getter = MethodHandles.privateLookupIn(type, MethodHandles.lookup())
                        .findGetter(type, "packetLimiter", field)
                        .asType(MethodType.methodType(Object.class, type));
                limiterGetter = getter;
            }
            return getter.bindTo(decoder);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    /**
     * The forwarder installed directly after an encoder of the class {@code encoderClass} on the channel's loop, with a
     * decoder of the class {@code decoderClass}, or null.
     */
    static @Nullable CompressedForwarder install(
            ChannelPipeline pipeline,
            String encoderClass,
            String decoderClass,
            boolean small,
            boolean heap,
            boolean consume) {
        return install(pipeline, encoderClass, decoderClass, small, heap, consume, refusal -> {});
    }

    /** As {@link #install}; {@code refused} hears why a present encoder got no forwarder. */
    static @Nullable CompressedForwarder install(
            ChannelPipeline pipeline,
            String encoderClass,
            String decoderClass,
            boolean small,
            boolean heap,
            boolean consume,
            Consumer<Refusal> refused) {
        if (!CompressedForwarder.supported() || pipeline.get(CompressedForwarder.NAME) != null) return null;
        ChannelHandlerContext encoder = pipeline.context(CompressedForwarder.COMPRESSION_ENCODER);
        if (encoder == null) return null;
        MethodHandle threshold = thresholdReader(encoder.handler(), encoderClass);
        if (threshold == null) {
            refused.accept(Refusal.THRESHOLD);
            return null;
        }
        ChannelHandlerContext decoder = pipeline.context(COMPRESSION_DECODER);
        if (decoder == null || !decoder.handler().getClass().getName().equals(decoderClass)) {
            refused.accept(Refusal.DECODER);
            return null;
        }
        if (encoder.executor() != pipeline.channel().eventLoop()
                || decoder.executor() != pipeline.channel().eventLoop()) {
            refused.accept(Refusal.EXECUTOR);
            return null;
        }
        if (!sharedCompressor(encoder.handler(), decoder.handler())) {
            refused.accept(Refusal.COMPRESSOR);
            return null;
        }
        var forwarder = new CompressedForwarder(threshold, encoder, decoder, small, heap, consume);
        if (forwarder.threshold() < 0) {
            refused.accept(Refusal.THRESHOLD);
            return null;
        }
        try {
            pipeline.addAfter(CompressedForwarder.COMPRESSION_ENCODER, CompressedForwarder.NAME, forwarder);
        } catch (RuntimeException failure) {
            CompressedForwarder.remove(pipeline, forwarder);
            refused.accept(Refusal.CHANGED);
            return null;
        }
        ChannelHandlerContext added = pipeline.context(forwarder);
        if (added == null || added.isRemoved() || encoder.isRemoved() || !CompressedForwarder.follows(added, encoder)) {
            CompressedForwarder.remove(pipeline, forwarder);
            refused.accept(Refusal.CHANGED);
            return null;
        }
        return forwarder;
    }

    /**
     * Whether {@code encoder} and {@code decoder} hold one non-null compressor, each read through its own getter, whose
     * {@code disposed} flag reads false.
     */
    static boolean sharedCompressor(Object encoder, Object decoder) {
        MethodHandle encoderReader = compressorReader(encoder, true);
        MethodHandle decoderReader = compressorReader(decoder, false);
        if (encoderReader == null || decoderReader == null) return false;
        try {
            Object compressor = (Object) encoderReader.invokeExact();
            return compressor != null && compressor == (Object) decoderReader.invokeExact() && !disposed(compressor);
        } catch (Throwable failure) {
            return false;
        }
    }

    /** Whether {@code compressor}'s {@code boolean disposed} field is true; throws when it cannot be read. */
    private static boolean disposed(Object compressor) throws Throwable {
        Class<?> type = compressor.getClass();
        MethodHandle getter = disposedGetter;
        if (getter == null || getter.type().parameterType(0) != type) {
            getter = MethodHandles.privateLookupIn(type, MethodHandles.lookup())
                    .findGetter(type, "disposed", boolean.class);
            disposedGetter = getter;
        }
        return (boolean) getter.bindTo(compressor).invokeExact();
    }

    /** A getter bound to {@code codec}'s {@code compressor} field, typed {@code ()Object}, or null; cached per role. */
    static @Nullable MethodHandle compressorReader(Object codec, boolean encoder) {
        try {
            Class<?> type = codec.getClass();
            MethodHandle getter = encoder ? encoderCompressorGetter : decoderCompressorGetter;
            if (getter == null || getter.type().parameterType(0) != type) {
                Class<?> field = type.getDeclaredField("compressor").getType();
                getter = MethodHandles.privateLookupIn(type, MethodHandles.lookup())
                        .findGetter(type, "compressor", field)
                        .asType(MethodType.methodType(Object.class, type));
                if (encoder) encoderCompressorGetter = getter;
                else decoderCompressorGetter = getter;
            }
            return getter.bindTo(codec);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    /** Why {@link #install} gives no forwarder to a client with a compression encoder. */
    enum Refusal {
        /** The encoder is not of the accepted class, or its threshold cannot be read. */
        THRESHOLD("Cannot read a client's compression threshold; its frames are compressed by Velocity"),
        /** The client has no compression decoder of the accepted class. */
        DECODER("A client's compression decoder is not Velocity's; its frames are compressed by Velocity"),
        /** The encoder or the decoder runs on an executor other than the client channel's event loop. */
        EXECUTOR("A client's compression encoder or decoder runs off its channel's event loop; its frames are"
                + " compressed by Velocity"),
        /** The encoder and the decoder do not hold one open compressor, or either compressor cannot be read. */
        COMPRESSOR("A client's compression encoder and decoder do not share one open compressor; its frames are"
                + " compressed by Velocity"),
        /** After the insert, the encoder is not the validated one or the forwarder does not directly follow it. */
        CHANGED("A client's compression encoder is not where the forwarder expects it after the insert; its frames are"
                + " compressed by Velocity");

        final String warning;

        Refusal(String warning) {
            this.warning = warning;
        }
    }

    /** Removes the forwarder and its backend link; runs on the client's loop or its packet owner. */
    static void uninstall(Channel client) {
        var pipeline = client.pipeline();
        if (!(pipeline.get(CompressedForwarder.NAME) instanceof CompressedForwarder forwarder)) return;
        try {
            forwarder.unlink();
        } finally {
            CompressedForwarder.remove(pipeline, forwarder);
        }
    }

    private void connected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        RegisteredServer server = event.getServer();
        Channel client = client(player);
        if (client == null) return;
        // Velocity resumes the backend's reads with a task it queues on this loop after every handler returns.
        schedule(client, decoderClass, () -> {
            Object inFlight = inFlight(player);
            return inFlight instanceof ServerConnection connection && connection.getServer() == server
                    ? inFlight
                    : null;
        });
    }

    private void postConnected(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        Channel client = client(player);
        if (client != null)
            schedule(client, decoderClass, () -> player.getCurrentServer().orElse(null));
    }

    private @Nullable Channel client(Player player) {
        try {
            return channels.apply(player);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /** Links the client's forwarder, on the client's loop, to the backend {@code server} names when the task runs. */
    private static void schedule(Channel client, String decoderClass, Supplier<Object> server) {
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
                if (backend != null) link(forwarder, client, backend, decoderClass);
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
    static void link(CompressedForwarder forwarder, Channel client, Channel backend, String decoderClass) {
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
        place(forwarder, client.pipeline(), backend, decoderClass);
    }

    /** Places the keep and mark around the backend's decoder of the class {@code decoderClass}; one loop for both. */
    static void place(CompressedForwarder forwarder, ChannelPipeline client, Channel backend, String decoderClass) {
        if (forwarder.disabled || forwarder.linkedTo(backend)) return;
        forwarder.unlink();
        var pipeline = backend.pipeline();
        ChannelHandlerContext decoder = pipeline.context(COMPRESSION_DECODER);
        if (decoder == null) {
            forwarder.linkStatus("backend-uncompressed");
            return;
        }
        if (!decoder.handler().getClass().getName().equals(decoderClass)) {
            forwarder.linkStatus("backend-decoder");
            return;
        }
        if (decoder.executor() != pipeline.channel().eventLoop()) {
            forwarder.linkStatus("backend-executor");
            return;
        }
        MethodHandle limiter = limiterReader(decoder.handler());
        if (CompressedKeep.limited(limiter)) {
            forwarder.linkStatus(CompressedKeep.LIMITED);
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
            forwarder.linkStatus("backend-install-failed");
            removePair(pipeline, keep, mark);
            return;
        }
        ChannelHandlerContext keepContext = pipeline.context(keep);
        ChannelHandlerContext markContext = pipeline.context(mark);
        if (keepContext == null
                || markContext == null
                || keepContext.isRemoved()
                || markContext.isRemoved()
                || decoder.isRemoved()
                || !CompressedForwarder.follows(decoder, keepContext)
                || !CompressedForwarder.follows(markContext, decoder)) {
            forwarder.linkStatus("backend-adjacency");
            removePair(pipeline, keep, mark);
            return;
        }
        keep.attach(keepContext, decoder, markContext, limiter);
        forwarder.link(backend, keep, mark);
    }

    private static void removePair(ChannelPipeline pipeline, CompressedKeep keep, CompressedMark mark) {
        try {
            CompressedForwarder.remove(pipeline, keep);
        } finally {
            CompressedForwarder.remove(pipeline, mark);
        }
    }
}
