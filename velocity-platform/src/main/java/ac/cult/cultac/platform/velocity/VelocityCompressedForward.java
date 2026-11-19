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
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.MessageToMessageDecoder;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/**
 * Forwards a backend's compressed frames to its client without compressing them again, when {@value
 * #ENABLED_PROPERTY} is true: a {@link CompressedForwarder} per Java client, a {@link CompressedKeep} on its backend.
 */
final class VelocityCompressedForward {
    static final String ENABLED_PROPERTY = "cult.velocity.forwardCompressed";
    static final String COMPRESSION_DECODER = "compression-decoder";
    static final String VELOCITY_ENCODER =
            "com.velocitypowered.proxy.protocol.netty.MinecraftCompressorAndLengthEncoder";
    static final String VELOCITY_DECODER = "com.velocitypowered.proxy.protocol.netty.MinecraftCompressDecoder";
    static final String VELOCITY_JAVA_COMPRESSOR = "com.velocitypowered.natives.compression.JavaVelocityCompressor";
    static final String VELOCITY_LIBDEFLATE_COMPRESSOR =
            "com.velocitypowered.natives.compression.LibdeflateVelocityCompressor";
    static final String VELOCITY_PRECONDITIONS = "com.velocitypowered.proxy.protocol.util.NettyPreconditions";
    static final String VELOCITY_QUIET_EXCEPTION = "com.velocitypowered.proxy.util.except.QuietDecoderException";

    private final ProxyServer proxy;
    private final Object plugin;
    private final Function<Player, Channel> channels;
    private final Class<?> encoderClass;
    private final Class<?> decoderClass;
    private final Compressors compressors;
    // Every decoder a keep links to is exactly decoderClass, so its handles are this one set.
    private final @Nullable CompressedKeep.Decode decode;
    private final AtomicBoolean[] warned = new AtomicBoolean[Refusal.values().length];
    private volatile Method inFlightMethod;
    private static volatile @Nullable MethodHandle thresholdGetter;
    private static volatile @Nullable MethodHandle decoderThresholdGetter;
    private static volatile @Nullable MethodHandle limiterGetter;
    private static volatile @Nullable MethodHandle encoderCompressorGetter;
    private static volatile @Nullable MethodHandle decoderCompressorGetter;
    private static volatile @Nullable MethodHandle disposedGetter;
    private static volatile @Nullable MethodHandle inflateMethod;
    private final EventHandler<ServerConnectedEvent> connected = this::connected;
    private final EventHandler<ServerPostConnectEvent> postConnected = this::postConnected;
    private boolean listening;

    VelocityCompressedForward(ProxyServer proxy, Object plugin, Function<Player, Channel> channels, Codecs codecs) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.channels = channels;
        this.encoderClass = codecs.encoder();
        this.decoderClass = codecs.decoder();
        this.compressors = codecs.compressors();
        this.decode = decode(decoderClass);
        for (int i = 0; i < warned.length; i++) warned[i] = new AtomicBoolean();
    }

    /** Null unless {@value #ENABLED_PROPERTY} is true; nothing is registered or installed then. */
    static @Nullable VelocityCompressedForward fromSystemProperties(
            ProxyServer proxy, Object plugin, Function<Player, Channel> channels) {
        if (!Boolean.getBoolean(ENABLED_PROPERTY)) return null;
        if (!CompressedForwarder.supported()) {
            LogUtil.warn("Cannot read the handler order or the pipelines' leak detection of this netty; compressed"
                    + " frames are not forwarded");
            return null;
        }
        ClassLoader loader = proxy.getClass().getClassLoader();
        Codecs codecs = Codecs.resolve(loader);
        if (codecs == null) {
            LogUtil.warn("Cannot find Velocity's compression classes; compressed frames are not forwarded");
            return null;
        }
        return new VelocityCompressedForward(proxy, plugin, channels, codecs);
    }

    /** The compression encoder, decoder and compressor classes the forwarding handlers accept, by identity. */
    record Codecs(Class<?> encoder, Class<?> decoder, Compressors compressors) {
        /** Velocity's four classes as {@code loader} resolves them, or null when one cannot be resolved. */
        static @Nullable Codecs resolve(@Nullable ClassLoader loader) {
            try {
                return new Codecs(
                        Class.forName(VELOCITY_ENCODER, false, loader),
                        Class.forName(VELOCITY_DECODER, false, loader),
                        new Compressors(
                                Class.forName(VELOCITY_JAVA_COMPRESSOR, false, loader),
                                Class.forName(VELOCITY_LIBDEFLATE_COMPRESSOR, false, loader)));
            } catch (ClassNotFoundException | LinkageError failure) {
                return null;
            }
        }
    }

    /**
     * Velocity's two compressor classes: {@code java} consumes the input it inflates and deflates, {@code libdeflate}
     * reads and writes through memory addresses.
     */
    record Compressors(Class<?> java, Class<?> libdeflate) {
        /** Whether {@code compressor}'s class is exactly one of the two classes. */
        boolean accepts(@Nullable Object compressor) {
            return is(compressor, java) || is(compressor, libdeflate);
        }

        /** Whether {@code compressor}'s class is exactly {@code type}. */
        static boolean is(@Nullable Object compressor, Class<?> type) {
            return compressor != null && compressor.getClass() == type;
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
        var forwarder = install(pipeline, encoderClass, decoderClass, compressors, this::refused);
        if (forwarder == null) return;
        adapter.compressedForwarder(forwarder);
        schedule(
                channel,
                decoderClass,
                compressors,
                decode,
                () -> player.getCurrentServer().orElse(null),
                this::refused);
    }

    /** Logs the first refusal of each kind, once across every loop; nothing the logger throws leaves this method. */
    void refused(Refusal refusal) {
        if (!warned[refusal.ordinal()].compareAndSet(false, true)) return;
        try {
            LogUtil.warn(refusal.warning);
        } catch (Throwable failure) {
            // The switch-off path runs no logger; its throw reaches neither the caller nor the loop.
        }
    }

    /**
     * Hands {@code warning} to {@code channel}'s loop as a task, which runs after the read or write that raises it; a
     * task the loop rejects is dropped.
     */
    static Runnable deferred(Channel channel, Runnable warning) {
        return () -> {
            try {
                channel.eventLoop().execute(warning);
            } catch (RejectedExecutionException closed) {
                // A shut-down loop runs no task, and its channel has nothing left to report.
            }
        };
    }

    /** A getter bound to {@code encoder}'s {@code int threshold} when it is exactly of {@code encoderClass}, or null. */
    static @Nullable MethodHandle thresholdReader(@Nullable Object encoder, Class<?> encoderClass) {
        if (encoder == null || encoder.getClass() != encoderClass) return null;
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

    /** A getter bound to {@code decoder}'s {@code int threshold}, or null. */
    static @Nullable MethodHandle decoderThresholdReader(Object decoder) {
        try {
            Class<?> type = decoder.getClass();
            MethodHandle getter = decoderThresholdGetter;
            if (getter == null || getter.type().parameterType(0) != type) {
                getter = MethodHandles.privateLookupIn(type, MethodHandles.lookup())
                        .findGetter(type, "threshold", int.class);
                decoderThresholdGetter = getter;
            }
            return getter.bindTo(decoder);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    /**
     * Velocity's handles a decode of the decoder class {@code type} completes with: {@code account(int)} of its
     * {@code packetLimiter} field's type, and {@value #VELOCITY_PRECONDITIONS}'s {@code checkFrame} and
     * {@value #VELOCITY_QUIET_EXCEPTION}'s constructor as the decoder's loader resolves them; null when one cannot be
     * bound.
     */
    static @Nullable CompressedKeep.Decode decode(Class<?> type) {
        try {
            ClassLoader loader = type.getClassLoader();
            Class<?> limiter = type.getDeclaredField("packetLimiter").getType();
            Class<?> preconditions = Class.forName(VELOCITY_PRECONDITIONS, false, loader);
            Class<?> quiet = Class.forName(VELOCITY_QUIET_EXCEPTION, false, loader);
            if (!DecoderException.class.isAssignableFrom(quiet)) return null;
            MethodHandle account = MethodHandles.privateLookupIn(limiter, MethodHandles.lookup())
                    .findVirtual(limiter, "account", MethodType.methodType(boolean.class, int.class))
                    .asType(MethodType.methodType(boolean.class, Object.class, int.class));
            MethodHandle check = MethodHandles.privateLookupIn(preconditions, MethodHandles.lookup())
                    .findStatic(
                            preconditions,
                            "checkFrame",
                            MethodType.methodType(void.class, boolean.class, String.class, Object.class, Object.class));
            MethodHandle constructor = MethodHandles.privateLookupIn(quiet, MethodHandles.lookup())
                    .findConstructor(quiet, MethodType.methodType(void.class, String.class))
                    .asType(MethodType.methodType(DecoderException.class, String.class));
            return new CompressedKeep.Decode(account, check, constructor);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            return null;
        }
    }

    /**
     * The keep's handles for {@code decoder}, whose limiter {@code limiter} reads, with its class's {@link #decode}
     * handles {@code decode}, when its compressor is exactly one of {@code compressors}' classes, or null.
     */
    static CompressedKeep.Codec codec(
            Object decoder,
            @Nullable MethodHandle limiter,
            Compressors compressors,
            @Nullable CompressedKeep.Decode decode) {
        MethodHandle compressorReader = compressorReader(decoder, false);
        MethodHandle threshold = decoderThresholdReader(decoder);
        if (limiter == null || compressorReader == null || threshold == null || decode == null) return null;
        try {
            Object compressor = (Object) compressorReader.invokeExact();
            if (!compressors.accepts(compressor)) return null;
            Class<?> type = compressor.getClass();
            MethodHandle inflate = inflateMethod;
            if (inflate == null || inflate.type().parameterType(0) != type) {
                inflate = MethodHandles.privateLookupIn(type, MethodHandles.lookup())
                        .findVirtual(
                                type,
                                "inflate",
                                MethodType.methodType(void.class, ByteBuf.class, ByteBuf.class, int.class));
                inflateMethod = inflate;
            }
            boolean address = Compressors.is(compressor, compressors.libdeflate());
            return new CompressedKeep.Codec(threshold, limiter, inflate.bindTo(compressor), address, decode);
        } catch (Throwable failure) {
            return null;
        }
    }

    /**
     * The forwarder installed, in a pipeline that touches no message, directly after an encoder exactly of
     * {@code encoderClass} on the channel's loop, with a decoder exactly of {@code decoderClass} sharing an open
     * compressor of one of {@code compressors}' classes, or null.
     */
    static @Nullable CompressedForwarder install(
            ChannelPipeline pipeline, Class<?> encoderClass, Class<?> decoderClass, Compressors compressors) {
        return install(pipeline, encoderClass, decoderClass, compressors, refusal -> {});
    }

    /** As {@link #install}; {@code refused} hears why a present encoder receives no forwarder. */
    static @Nullable CompressedForwarder install(
            ChannelPipeline pipeline,
            Class<?> encoderClass,
            Class<?> decoderClass,
            Compressors compressors,
            Consumer<Refusal> refused) {
        return install(pipeline, encoderClass, decoderClass, compressors, CompressedKeep.NETTY_ALLOCATORS, refused);
    }

    /**
     * As {@link #install}; the forwarder forwards only under a client allocator {@code trustedAllocators} accepts, and
     * {@code refused} also hears the first write it refuses.
     */
    static @Nullable CompressedForwarder install(
            ChannelPipeline pipeline,
            Class<?> encoderClass,
            Class<?> decoderClass,
            Compressors compressors,
            Predicate<ByteBufAllocator> trustedAllocators,
            Consumer<Refusal> refused) {
        if (!CompressedForwarder.supported() || pipeline.get(CompressedForwarder.NAME) != null) return null;
        ChannelHandlerContext encoder = pipeline.context(CompressedForwarder.COMPRESSION_ENCODER);
        if (encoder == null) return null;
        if (CompressedForwarder.touches(pipeline)) {
            refused.accept(Refusal.LEAK_DETECTION);
            return null;
        }
        MethodHandle threshold = thresholdReader(encoder.handler(), encoderClass);
        if (threshold == null) {
            refused.accept(Refusal.THRESHOLD);
            return null;
        }
        ChannelHandlerContext decoder = pipeline.context(COMPRESSION_DECODER);
        if (decoder == null || decoder.handler().getClass() != decoderClass) {
            refused.accept(Refusal.DECODER);
            return null;
        }
        if (encoder.executor() != pipeline.channel().eventLoop()
                || decoder.executor() != pipeline.channel().eventLoop()
                || !CompressedForwarder.onLoop(pipeline)) {
            refused.accept(Refusal.EXECUTOR);
            return null;
        }
        Object compressor = sharedCompressor(encoder.handler(), decoder.handler(), compressors);
        MethodHandle disposed = compressor == null ? null : disposedReader(compressor);
        if (disposed == null) {
            refused.accept(Refusal.COMPRESSOR);
            return null;
        }
        boolean consume = Compressors.is(compressor, compressors.java());
        var forwarder = new CompressedForwarder(
                threshold,
                encoder,
                decoder,
                consume,
                disposed,
                trustedAllocators,
                deferred(pipeline.channel(), () -> refused.accept(Refusal.CLIENT_UNTRUSTED)));
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
     * The one compressor {@code encoder} and {@code decoder} hold, each read through its own getter, when it is exactly
     * of one of {@code compressors}' classes and its {@code disposed} flag reads false, or null.
     */
    static @Nullable Object sharedCompressor(Object encoder, Object decoder, Compressors compressors) {
        MethodHandle encoderReader = compressorReader(encoder, true);
        MethodHandle decoderReader = compressorReader(decoder, false);
        if (encoderReader == null || decoderReader == null) return null;
        try {
            Object compressor = (Object) encoderReader.invokeExact();
            if (compressor == null
                    || compressor != (Object) decoderReader.invokeExact()
                    || !compressors.accepts(compressor)) return null;
            MethodHandle disposed = disposedReader(compressor);
            return disposed != null && !(boolean) disposed.invokeExact() ? compressor : null;
        } catch (Throwable failure) {
            return null;
        }
    }

    /** A getter bound to {@code compressor}'s {@code boolean disposed} field, typed {@code ()boolean}, or null. */
    static @Nullable MethodHandle disposedReader(Object compressor) {
        try {
            Class<?> type = compressor.getClass();
            MethodHandle getter = disposedGetter;
            if (getter == null || getter.type().parameterType(0) != type) {
                getter = MethodHandles.privateLookupIn(type, MethodHandles.lookup())
                        .findGetter(type, "disposed", boolean.class);
                disposedGetter = getter;
            }
            return getter.bindTo(compressor);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
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

    /**
     * Why {@link #install} gives no forwarder to a client with a compression encoder, why a forwarder disables, or why
     * a backend's keep holds no frames.
     */
    enum Refusal {
        /** The encoder is not of the accepted class, or its threshold cannot be read. */
        THRESHOLD("Cannot read a client's compression threshold; its frames are compressed by Velocity"),
        /** The client has no compression decoder of the accepted class. */
        DECODER("A client's compression decoder is not Velocity's; its frames are compressed by Velocity"),
        /** The encoder, the decoder or another handler runs on an executor other than the client channel's event loop. */
        EXECUTOR("A client's compression encoder, decoder or another handler runs off its channel's event loop; its"
                + " frames are compressed by Velocity"),
        /**
         * The encoder and the decoder do not hold one open compressor of Velocity's classes, or either compressor
         * cannot be read.
         */
        COMPRESSOR("A client's compression encoder and decoder do not share one open Velocity compressor; its frames"
                + " are compressed by Velocity"),
        /** After the insert, the encoder is not the validated one or the forwarder does not directly follow it. */
        CHANGED("A client's compression encoder is not where the forwarder expects it after the insert; its frames are"
                + " compressed by Velocity"),
        /** The client's pipeline was built with netty's leak detection enabled, or how it was built cannot be read. */
        LEAK_DETECTION("A client's pipeline was built with Netty's leak detection enabled; its frames are compressed by"
                + " Velocity"),
        /**
         * Netty's leak detection is enabled, or the backend's allocator or netty's leak detector is not of netty's own
         * classes; the keep unlinks.
         */
        UNTRUSTED("Netty's leak detection is enabled, or a backend's allocator or leak detector is not netty's own; its"
                + " frames are compressed by Velocity"),
        /**
         * Netty's leak detection is enabled, or a client's allocator or netty's leak detector is not of netty's own
         * classes; the forwarder disables.
         */
        CLIENT_UNTRUSTED(
                "Netty's leak detection is enabled, or a client's allocator or leak detector is not netty's own;"
                        + " its frames are framed and compressed by Velocity");

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
        schedule(
                client,
                decoderClass,
                compressors,
                decode,
                () -> {
                    Object inFlight = inFlight(player);
                    return inFlight instanceof ServerConnection connection && connection.getServer() == server
                            ? inFlight
                            : null;
                },
                this::refused);
    }

    private void postConnected(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        Channel client = client(player);
        if (client != null)
            schedule(
                    client,
                    decoderClass,
                    compressors,
                    decode,
                    () -> player.getCurrentServer().orElse(null),
                    this::refused);
    }

    private @Nullable Channel client(Player player) {
        try {
            return channels.apply(player);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /** Links the client's forwarder, on the client's loop, to the backend {@code server} names when the task runs. */
    private static void schedule(
            Channel client,
            Class<?> decoderClass,
            Compressors compressors,
            @Nullable CompressedKeep.Decode decode,
            Supplier<Object> server,
            Consumer<Refusal> refused) {
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
                if (backend != null) link(forwarder, client, backend, decoderClass, compressors, decode, refused);
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
    static void link(
            CompressedForwarder forwarder,
            Channel client,
            Channel backend,
            Class<?> decoderClass,
            Compressors compressors) {
        link(forwarder, client, backend, decoderClass, compressors, decode(decoderClass), refusal -> {});
    }

    /**
     * As {@link #link}; {@code decode} holds the {@link #decode} handles of {@code decoderClass}, and {@code refused}
     * hears why the backend's keep holds no frames.
     */
    static void link(
            CompressedForwarder forwarder,
            Channel client,
            Channel backend,
            Class<?> decoderClass,
            Compressors compressors,
            @Nullable CompressedKeep.Decode decode,
            Consumer<Refusal> refused) {
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
        place(
                forwarder,
                client.pipeline(),
                backend,
                decoderClass,
                compressors,
                decode,
                CompressedKeep.NETTY_ALLOCATORS,
                refused);
    }

    /**
     * Places the keep, in a backend pipeline that touches no message, before the backend's decoder exactly of
     * {@code decoderClass} holding a compressor of one of {@code compressors}' classes; one loop for both.
     */
    static void place(
            CompressedForwarder forwarder,
            ChannelPipeline client,
            Channel backend,
            Class<?> decoderClass,
            Compressors compressors) {
        place(forwarder, client, backend, decoderClass, compressors, CompressedKeep.NETTY_ALLOCATORS);
    }

    /** As {@link #place}; the keep holds frames only under an allocator {@code trustedAllocators} accepts. */
    static void place(
            CompressedForwarder forwarder,
            ChannelPipeline client,
            Channel backend,
            Class<?> decoderClass,
            Compressors compressors,
            Predicate<ByteBufAllocator> trustedAllocators) {
        place(forwarder, client, backend, decoderClass, compressors, trustedAllocators, refusal -> {});
    }

    /** As {@link #place}; {@code refused} hears each read whose trust check refuses, which unlinks the keep. */
    static void place(
            CompressedForwarder forwarder,
            ChannelPipeline client,
            Channel backend,
            Class<?> decoderClass,
            Compressors compressors,
            Predicate<ByteBufAllocator> trustedAllocators,
            Consumer<Refusal> refused) {
        place(forwarder, client, backend, decoderClass, compressors, decode(decoderClass), trustedAllocators, refused);
    }

    /** As {@link #place}; {@code decode} holds the {@link #decode} handles of {@code decoderClass}, or is null. */
    static void place(
            CompressedForwarder forwarder,
            ChannelPipeline client,
            Channel backend,
            Class<?> decoderClass,
            Compressors compressors,
            @Nullable CompressedKeep.Decode decode,
            Predicate<ByteBufAllocator> trustedAllocators,
            Consumer<Refusal> refused) {
        if (forwarder.disabled || forwarder.linkedTo(backend)) return;
        forwarder.unlink();
        var pipeline = backend.pipeline();
        ChannelHandlerContext decoder = pipeline.context(COMPRESSION_DECODER);
        if (decoder == null) {
            forwarder.linkStatus("backend-uncompressed");
            return;
        }
        Object handler = decoder.handler();
        if (handler.getClass() != decoderClass
                || !(handler instanceof MessageToMessageDecoder<?>)
                || !CompressedKeep.supported()) {
            forwarder.linkStatus("backend-decoder");
            return;
        }
        if (decoder.executor() != pipeline.channel().eventLoop() || !CompressedForwarder.onLoop(pipeline)) {
            forwarder.linkStatus("backend-executor");
            return;
        }
        MethodHandle limiter = limiterReader(handler);
        if (CompressedKeep.limited(limiter)) {
            forwarder.linkStatus(CompressedKeep.LIMITED);
            return;
        }
        if (decode == null) {
            forwarder.linkStatus("backend-decoder");
            return;
        }
        CompressedKeep.Codec codec = codec(handler, limiter, compressors, decode);
        if (codec == null) {
            forwarder.linkStatus("backend-compressor");
            return;
        }
        if (!CompressedForwarder.adjacent(client)) {
            forwarder.disable("adjacency");
            return;
        }
        if (pipeline.get(CompressedKeep.NAME) != null) {
            forwarder.linkStatus("backend-occupied");
            return;
        }
        if (CompressedForwarder.touches(pipeline)) {
            forwarder.linkStatus(CompressedKeep.LEAK_DETECTION);
            return;
        }
        var keep = new CompressedKeep(
                forwarder, trustedAllocators, deferred(backend, () -> refused.accept(Refusal.UNTRUSTED)));
        try {
            pipeline.addBefore(COMPRESSION_DECODER, CompressedKeep.NAME, keep);
        } catch (RuntimeException failure) {
            forwarder.linkStatus("backend-install-failed");
            CompressedForwarder.remove(pipeline, keep);
            return;
        }
        ChannelHandlerContext keepContext = pipeline.context(keep);
        if (keepContext == null
                || keepContext.isRemoved()
                || decoder.isRemoved()
                || !CompressedForwarder.follows(decoder, keepContext)) {
            forwarder.linkStatus("backend-adjacency");
            CompressedForwarder.remove(pipeline, keep);
            return;
        }
        keep.attach(keepContext, decoder, codec);
        forwarder.link(backend, keep);
    }
}
