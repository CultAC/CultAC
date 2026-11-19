package ac.cult.cultac.platform.velocity;

import io.netty.buffer.AbstractByteBuf;
import io.netty.buffer.AdaptiveByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.util.IllegalReferenceCountException;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Set;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

/**
 * Sits on a backend channel directly before Velocity's compression decoder and completes the decode of each forwardable
 * compressed frame itself with the decoder's compressor, publishing a forwardable result to the forwarder; every frame
 * refused before its claim is read goes to the decoder untouched.
 */
final class CompressedKeep extends ChannelInboundHandlerAdapter {
    static final String NAME = "cult-compressed-keep";
    // A packet limiter runs inside the decoder between its inflate and its publish.
    static final String LIMITED = "backend-limiter";
    // Leak detection is enabled, or the backend's allocator or netty's leak detector is not of netty's own classes.
    static final String UNTRUSTED = "backend-untrusted";
    // The backend's pipeline was built with leak detection enabled, or how it was built cannot be read.
    static final String LEAK_DETECTION = "backend-leak-detection";
    // Netty's own allocators; any other can run code between the keep's checks and its publish.
    private static final Set<Class<?>> ALLOCATORS =
            Set.of(AdaptiveByteBufAllocator.class, PooledByteBufAllocator.class, UnpooledByteBufAllocator.class);
    // A custom leak detector's trackers run their own code in every access to a tracked buffer.
    private static final boolean NETTY_LEAK_DETECTOR = nettyLeakDetector(bufferLeakDetector());
    /** Whether an allocator is exactly of one of netty's own allocator classes and buffers use netty's leak detector. */
    static final Predicate<ByteBufAllocator> NETTY_CLASSES =
            alloc -> NETTY_LEAK_DETECTOR && ALLOCATORS.contains(alloc.getClass());
    /**
     * As {@link #NETTY_CLASSES} while netty's leak detection is disabled, when their allocations run no leak report and
     * so no log appender or leak listener.
     */
    static final Predicate<ByteBufAllocator> NETTY_ALLOCATORS =
            NETTY_CLASSES.and(alloc -> !ResourceLeakDetector.isEnabled());
    // A frame of any other class runs its own code in the keep's reads, copy and release.
    private static final ClassValue<Boolean> NETTY_BUFFERS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return type.getClassLoader() == ByteBuf.class.getClassLoader()
                    && type.getPackageName().equals(ByteBuf.class.getPackageName())
                    && !CompositeByteBuf.class.isAssignableFrom(type)
                    // FixedCompositeByteBuf holds buffers of any class and exposes none through unwrap().
                    && !type.getName().equals("io.netty.buffer.FixedCompositeByteBuf");
        }
    };
    // MessageToMessageDecoder's read flags, which its channelReadComplete reads with auto-read off.
    private static final @Nullable VarHandle DECODE_CALLED = flag("decodeCalled");
    private static final @Nullable VarHandle MESSAGE_PRODUCED = flag("messageProduced");
    // Netty invokes a context's handler only in the states its invokeHandler() reads from these fields.
    private static final @Nullable VarHandle HANDLER_STATE = contextField("handlerState", int.class);
    private static final @Nullable VarHandle ORDERED = contextField("ordered", boolean.class);
    private static final int ADD_COMPLETE = contextConstant("ADD_COMPLETE");
    private static final int ADD_PENDING = contextConstant("ADD_PENDING");
    private static final InternalLogger CONTEXT_LOGGER =
            InternalLoggerFactory.getInstance("io.netty.channel.AbstractChannelHandlerContext");

    /**
     * The backend decoder's threshold and limiter getters and its compressor's {@code inflate(ByteBuf, ByteBuf, int)},
     * each bound, and the decoder's failure handles; {@code address} when the compressor reads through a memory address.
     */
    record Codec(MethodHandle threshold, MethodHandle limiter, MethodHandle inflate, boolean address, Decode decode) {}

    /**
     * Velocity's handles a decode completes with: {@code account} is the limiter's {@code (Object, int)boolean},
     * {@code check} its {@code checkFrame(boolean, String, Object, Object)}, {@code quiet} the limiter refusal's
     * {@code (String)DecoderException} constructor.
     */
    record Decode(MethodHandle account, MethodHandle check, MethodHandle quiet) {}

    private final CompressedForwarder forwarder;
    private final Predicate<ByteBufAllocator> trustedAllocators;
    private final Runnable untrusted;
    private @Nullable ChannelHandlerContext keepContext, decoderContext;
    private @Nullable MessageToMessageDecoder<?> decoder;
    private @Nullable Codec codec;
    // A trust refusal of the read in decode, reported after its fire.
    private boolean distrusted;

    CompressedKeep(CompressedForwarder forwarder) {
        this(forwarder, NETTY_ALLOCATORS);
    }

    /** A keep that holds frames only while {@code trustedAllocators} accepts the backend channel's allocator. */
    CompressedKeep(CompressedForwarder forwarder, Predicate<ByteBufAllocator> trustedAllocators) {
        this(forwarder, trustedAllocators, () -> {});
    }

    /** As the two-argument keep; {@code untrusted} runs at each read whose trust check refuses. */
    CompressedKeep(CompressedForwarder forwarder, Predicate<ByteBufAllocator> trustedAllocators, Runnable untrusted) {
        this.forwarder = forwarder;
        this.trustedAllocators = trustedAllocators;
        this.untrusted = untrusted;
    }

    private static @Nullable VarHandle flag(String name) {
        try {
            return MethodHandles.privateLookupIn(MessageToMessageDecoder.class, MethodHandles.lookup())
                    .findVarHandle(MessageToMessageDecoder.class, name, boolean.class);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            return null;
        }
    }

    private static Class<?> contextClass() throws ClassNotFoundException {
        return Class.forName(
                "io.netty.channel.AbstractChannelHandlerContext", false, ChannelHandlerContext.class.getClassLoader());
    }

    private static @Nullable VarHandle contextField(String name, Class<?> type) {
        try {
            Class<?> context = contextClass();
            return MethodHandles.privateLookupIn(context, MethodHandles.lookup())
                    .findVarHandle(context, name, type);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            return null;
        }
    }

    private static int contextConstant(String name) {
        try {
            Class<?> context = contextClass();
            return (int) MethodHandles.privateLookupIn(context, MethodHandles.lookup())
                    .findStaticVarHandle(context, name, int.class)
                    .get();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            return -1;
        }
    }

    /** Whether netty invokes {@code ctx}'s handler, as its context's {@code invokeHandler()} decides. */
    static boolean invoked(ChannelHandlerContext ctx) {
        try {
            int state = (int) HANDLER_STATE.getVolatile(ctx);
            return state == ADD_COMPLETE || (!(boolean) ORDERED.get(ctx) && state == ADD_PENDING);
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    private static @Nullable Object bufferLeakDetector() {
        try {
            return MethodHandles.privateLookupIn(AbstractByteBuf.class, MethodHandles.lookup())
                    .findStaticVarHandle(AbstractByteBuf.class, "leakDetector", ResourceLeakDetector.class)
                    .get();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            return null;
        }
    }

    /** Whether {@code detector} is exactly netty's own leak detector class, whose trackers only record traces. */
    static boolean nettyLeakDetector(@Nullable Object detector) {
        return detector != null && detector.getClass() == ResourceLeakDetector.class;
    }

    /**
     * Whether this netty lets the keep set the flags a decoder's read completion reads and read whether it invokes a
     * context's handler.
     */
    static boolean supported() {
        return DECODE_CALLED != null
                && MESSAGE_PRODUCED != null
                && HANDLER_STATE != null
                && ORDERED != null
                && ADD_COMPLETE >= 0
                && ADD_PENDING >= 0;
    }

    /** Caches the contexts of this keep and the compression decoder, a {@link MessageToMessageDecoder}, and its codec. */
    void attach(@Nullable ChannelHandlerContext keep, @Nullable ChannelHandlerContext decoder, @Nullable Codec codec) {
        keepContext = keep;
        decoderContext = decoder;
        this.decoder =
                decoder != null && decoder.handler() instanceof MessageToMessageDecoder<?> handler ? handler : null;
        this.codec = codec;
    }

    /** Whether {@code limiter} reads a packet limiter set on the decoder, or cannot be read. */
    static boolean limited(@Nullable MethodHandle limiter) {
        if (limiter == null) return true;
        try {
            return (Object) limiter.invokeExact() != null;
        } catch (Throwable failure) {
            return true;
        }
    }

    /**
     * The data length {@code frame} claims between {@code start} and {@code end} when it has one NIO buffer, as
     * Velocity's Java inflater requires, a memory address when the compressor reads through one, and a minimally
     * encoded claim the forwarder could send at or above the backend decoder's threshold; else -1.
     */
    private int claimed(ByteBuf frame, Codec codec, int start, int end) {
        if (frame.nioBufferCount() != 1) return -1;
        // Velocity copies a frame without an address for libdeflate between its own two allocations.
        if (codec.address() && !frame.hasMemoryAddress()) return -1;
        int n = CompressedForwarder.dataLength(frame, start, end);
        // MAX_FORWARDED_LENGTH is below both of Velocity's uncompressed caps.
        if (n <= 0 || n < forwarder.threshold() || n > CompressedForwarder.MAX_FORWARDED_LENGTH) return -1;
        // The private frame writes the claim minimally, so only a minimal claim keeps the backend's bytes.
        if (CompressedForwarder.dataLengthSize(frame, start, end) != CompressedForwarder.varIntSize(n)) return -1;
        // Velocity's decoder checks its threshold once, before its allocation.
        return atThreshold(codec, n) ? n : -1;
    }

    /** Whether {@code n} is at or above the backend decoder's current threshold, as {@code codec} reads it. */
    private static boolean atThreshold(Codec codec, int n) {
        try {
            return n >= (int) codec.threshold().invokeExact();
        } catch (Throwable failure) {
            return false;
        }
    }

    /**
     * Whether {@code alloc}, the channel's allocator read once for this read, passes {@link #trustedAllocators}.
     * Netty's own, with leak detection disabled, run no code outside netty and the JDK in their calls.
     */
    private boolean trusted(ByteBufAllocator alloc) {
        return trustedAllocators.test(alloc);
    }

    /** Whether {@code frame} and each buffer it unwraps to are of netty's own buffer classes other than its composites. */
    static boolean nettyFrame(ByteBuf frame) {
        try {
            for (ByteBuf buf = frame; buf != null; buf = buf.unwrap())
                if (!NETTY_BUFFERS.get(buf.getClass())) return false;
        } catch (IllegalReferenceCountException released) {
            // A released pooled slice or duplicate throws from unwrap().
            return false;
        }
        return true;
    }

    /** Whether the cached decoder is in the pipeline directly after this keep. */
    boolean adjacent() {
        ChannelHandlerContext keep = keepContext;
        ChannelHandlerContext decoder = decoderContext;
        return keep != null
                && decoder != null
                && !keep.isRemoved()
                && !decoder.isRemoved()
                && CompressedForwarder.follows(decoder, keep);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // The class check runs before any method of the frame; a released frame passes on for the decoder to refuse.
        if (forwarder.disabled
                || !(msg instanceof ByteBuf frame)
                || !nettyFrame(frame)
                || frame.refCnt() == 0
                || !frame.isReadable()) {
            ctx.fireChannelRead(msg);
            return;
        }
        Codec codec = this.codec;
        MessageToMessageDecoder<?> decoder = this.decoder;
        ChannelHandlerContext output = decoderContext;
        if (ctx != keepContext || codec == null || decoder == null || output == null || !adjacent()) {
            refuse(ctx, msg, null);
            return;
        }
        // Netty passes a frame over a decoder whose handler it does not invoke yet.
        if (!invoked(output)) {
            ctx.fireChannelRead(msg);
            return;
        }
        if (limited(codec.limiter())) {
            refuse(ctx, msg, LIMITED);
            return;
        }
        // The trust checks and the keep's three allocations of this read use this one allocator.
        ByteBufAllocator alloc = ctx.alloc();
        if (!trusted(alloc)) {
            refuse(ctx, msg, UNTRUSTED);
            return;
        }
        int start = frame.readerIndex();
        int end = frame.writerIndex();
        int n = claimed(frame, codec, start, end);
        if (n <= 0) {
            ctx.fireChannelRead(msg);
            return;
        }
        // Velocity's decoder reads the claim and retains its input before its first allocation.
        int body = start + CompressedForwarder.dataLengthSize(frame, start, end);
        frame.readerIndex(body);
        try {
            frame.retain();
        } catch (IllegalReferenceCountException overflow) {
            frame.readerIndex(start);
            ctx.fireChannelRead(msg);
            return;
        }
        decode(ctx, output, frame, codec, decoder, alloc, n, body, end);
    }

    /** Unlinks with {@code status}, or {@code backend-adjacency} when null, and passes {@code msg} on untouched. */
    private void refuse(ChannelHandlerContext ctx, Object msg, @Nullable String status) {
        try {
            if (status == null) forwarder.unlinkDetached(this);
            else forwarder.unlinkDetached(this, status);
            if (UNTRUSTED.equals(status)) untrusted.run();
        } finally {
            ctx.fireChannelRead(msg);
        }
    }

    /**
     * Completes Velocity's decode of {@code frame}, whose claim {@code n} the keep has read and which it has retained,
     * as {@code MinecraftCompressDecoder.decode} and {@code MessageToMessageDecoder.channelRead} complete it: inflates
     * into the decoder's allocation, checks the length, accounts the limiter, releases the keep's reference and the
     * input, and fires the result from {@code output}, the decoder's context, with the mark when it can be forwarded, or
     * delivers the failure from that context. Every buffer the keep allocates comes from {@code alloc}.
     */
    private void decode(
            ChannelHandlerContext ctx,
            ChannelHandlerContext output,
            ByteBuf frame,
            Codec codec,
            MessageToMessageDecoder<?> decoder,
            ByteBufAllocator alloc,
            int n,
            int body,
            int end)
            throws Exception {
        DECODE_CALLED.set(decoder, true);
        ByteBuf inflated = null;
        ByteBuf client = null;
        ByteBuf copy = null;
        boolean held = false;
        boolean refusedTrust = false;
        Throwable failure = null;
        try {
            try {
                ByteBuf uncompressed = destination(alloc, frame, n);
                try {
                    client = forwarder.frameBuffer(alloc, CompressedForwarder.varIntSize(n) + end - body);
                    if (client != null) copy = allocate(alloc, n);
                    boolean forwardable = forwardable(alloc, frame, codec, body, end);
                    held = forwardable
                            && copy != null
                            && heldInflate(codec, frame, body, end - body, n, client, uncompressed, copy);
                    refusedTrust = distrusted;
                    distrusted = false;
                    if (!held) {
                        uncompressed.clear();
                        inflate(codec, frame, uncompressed, n);
                    }
                    int written = uncompressed.writerIndex();
                    if (written != n) mismatch(codec, written, n);
                    account(ctx, codec, n);
                    inflated = uncompressed;
                } catch (Exception thrown) {
                    uncompressed.release();
                    throw thrown;
                } finally {
                    frame.release();
                }
            } finally {
                frame.release();
            }
        } catch (DecoderException thrown) {
            failure = thrown;
        } catch (Exception thrown) {
            failure = new DecoderException(thrown);
        } catch (Error thrown) {
            failure = thrown;
        }
        boolean published = held && inflated != null;
        try {
            try {
                Throwable released = null;
                if (inflated != null) {
                    MESSAGE_PRODUCED.set(decoder, true);
                    try {
                        if (published) released = publish(output, inflated, copy, client);
                        else output.fireChannelRead(inflated);
                    } catch (Throwable fired) {
                        failure = fired;
                    }
                }
                if (failure == null) failure = released;
                else if (released != null) failure.addSuppressed(released);
            } finally {
                if (!published) {
                    Throwable released = CompressedForwarder.release(client, null);
                    released = CompressedForwarder.release(copy, released);
                    if (failure == null) failure = released;
                    else if (released != null) failure.addSuppressed(released);
                }
            }
            if (failure != null) deliver(output, failure);
        } finally {
            if (refusedTrust) untrusted.run();
        }
    }

    /**
     * Delivers {@code cause} as netty delivers a failure thrown from the read of {@code output}'s handler: to that
     * handler's {@code exceptionCaught} when netty invokes it, else onward from {@code output}.
     */
    @SuppressWarnings("deprecation")
    private static void deliver(ChannelHandlerContext output, Throwable cause) {
        if (!invoked(output)) {
            output.fireExceptionCaught(cause);
            return;
        }
        try {
            output.handler().exceptionCaught(output, cause);
        } catch (Throwable error) {
            if (CONTEXT_LOGGER.isDebugEnabled()) {
                CONTEXT_LOGGER.debug(
                        "An exception was thrown by a user handler's exceptionCaught() method while handling the"
                                + " following exception:",
                        cause);
            } else if (CONTEXT_LOGGER.isWarnEnabled()) {
                CONTEXT_LOGGER.warn(
                        "An exception '{}' [enable DEBUG level for full stacktrace] was thrown by a user handler's"
                                + " exceptionCaught() method while handling the following exception:",
                        error,
                        cause);
            }
        }
    }

    /** The decoder's output buffer of {@code n} bytes; when the allocation throws, the keep's reference is released. */
    private static ByteBuf destination(ByteBufAllocator alloc, ByteBuf frame, int n) {
        try {
            return alloc.directBuffer(n);
        } catch (RuntimeException | Error failure) {
            Throwable kept = CompressedForwarder.release(frame, null);
            if (kept != null) failure.addSuppressed(kept);
            throw failure;
        }
    }

    /**
     * Whether the frame can be forwarded after the keep's allocations: the decoder directly after the keep, no limiter,
     * a trusted allocator, and {@code frame} as the keep left it; a refusal of the first three unlinks.
     */
    private boolean forwardable(ByteBufAllocator alloc, ByteBuf frame, Codec codec, int body, int end) {
        if (!adjacent()) {
            forwarder.unlinkDetached(this);
            return false;
        }
        if (limited(codec.limiter())) {
            forwarder.unlinkDetached(this, LIMITED);
            return false;
        }
        if (!trusted(alloc)) {
            forwarder.unlinkDetached(this, UNTRUSTED);
            distrusted = true;
            return false;
        }
        return unchanged(frame, codec, body, end);
    }

    /**
     * Whether {@code frame} is still referenced, its reader at {@code body} past the claim and its writer at {@code end},
     * with the buffer shape {@link #claimed} accepts.
     */
    private static boolean unchanged(ByteBuf frame, Codec codec, int body, int end) {
        try {
            return frame.refCnt() != 0
                    && frame.readerIndex() == body
                    && frame.writerIndex() == end
                    && frame.nioBufferCount() == 1
                    && (!codec.address() || frame.hasMemoryAddress());
        } catch (RuntimeException released) {
            return false;
        }
    }

    /**
     * Publishes {@code inflated}, its indices, {@code copy} and {@code client}, fires it and restores the mark; returns
     * what releasing the copy or an untaken frame threw after a fire that returned, which a throwing fire rethrows.
     */
    private @Nullable Throwable publish(ChannelHandlerContext output, ByteBuf inflated, ByteBuf copy, ByteBuf client) {
        CompressedForwarder target = forwarder;
        ByteBuf previous = target.markBuf;
        int previousReader = target.markReader();
        int previousWriter = target.markWriter();
        ByteBuf previousCopy = target.markCopy();
        ByteBuf previousFrame = target.markFrame();
        target.mark(inflated, inflated.readerIndex(), inflated.writerIndex(), copy, client);
        boolean fired = false;
        Throwable failure;
        try {
            output.fireChannelRead(inflated);
            fired = true;
        } finally {
            boolean untaken = target.markFrame() == client;
            target.mark(previous, previousReader, previousWriter, previousCopy, previousFrame);
            failure = CompressedForwarder.release(copy, null);
            if (untaken) failure = CompressedForwarder.release(client, failure);
            if (!fired) CompressedForwarder.rethrow(failure);
        }
        return failure;
    }

    /** A new buffer of {@code n} bytes for the comparison copy, or null when it cannot be allocated. */
    private static @Nullable ByteBuf allocate(ByteBufAllocator alloc, int n) {
        try {
            return alloc.buffer(n, n);
        } catch (Throwable failure) {
            return null;
        }
    }

    /**
     * Frames the claim {@code n} and the {@code length} body bytes of {@code frame} at {@code body} into {@code client},
     * inflates that copy of the body into {@code inflated} with the decoder's compressor, copies the result into
     * {@code copy} and moves {@code frame}'s reader as the compressor moves its source; false when a step fails.
     */
    private static boolean heldInflate(
            Codec codec, ByteBuf frame, int body, int length, int n, ByteBuf client, ByteBuf inflated, ByteBuf copy) {
        // From the first read of the frame's body to the publish only netty's buffers and Velocity's compressor run.
        try {
            CompressedForwarder.fillFrame(client, n, frame, body, length);
            ByteBuf source = client.slice(client.writerIndex() - length, length);
            codec.inflate().invokeExact(source, inflated, n);
            if (inflated.writerIndex() != n) return false;
            copy.writeBytes(inflated, inflated.readerIndex(), n);
            frame.readerIndex(Math.min(body + source.readerIndex(), frame.writerIndex()));
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    /** Inflates {@code source} at its current indices into {@code destination}, as the decoder's compressor call does. */
    private static void inflate(Codec codec, ByteBuf source, ByteBuf destination, int n) throws Exception {
        try {
            codec.inflate().invokeExact(source, destination, n);
        } catch (Exception | Error failure) {
            throw failure;
        } catch (Throwable other) {
            throw new UndeclaredThrowableException(other);
        }
    }

    /** Raises the decoder's failure for an inflated length {@code written} other than the claim {@code n}. */
    private static void mismatch(Codec codec, int written, int n) throws Exception {
        try {
            codec.decode()
                    .check()
                    .invokeExact(
                            false,
                            "Decompressed size %s does not match claimed uncompressed size %s",
                            (Object) written,
                            (Object) n);
        } catch (Exception | Error failure) {
            throw failure;
        } catch (Throwable other) {
            throw new UndeclaredThrowableException(other);
        }
    }

    /** Accounts {@code n} bytes with the decoder's limiter when one is set, raising its refusal as the decoder does. */
    private static void account(ChannelHandlerContext ctx, Codec codec, int n) throws Exception {
        try {
            Object limiter = (Object) codec.limiter().invokeExact();
            if (limiter != null && !(boolean) codec.decode().account().invokeExact(limiter, n))
                throw (DecoderException) codec.decode()
                        .quiet()
                        .invokeExact("Rate limit exceeded while processing packets for %s"
                                .formatted(ctx.channel().remoteAddress()));
        } catch (Exception | Error failure) {
            throw failure;
        } catch (Throwable other) {
            throw new UndeclaredThrowableException(other);
        }
    }
}
