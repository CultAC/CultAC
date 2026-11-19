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
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Set;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

/**
 * Sits on a backend channel directly before Velocity's compression decoder, inflates each forwardable compressed frame
 * itself with the decoder's compressor, publishes the result with its private client frame to the forwarder, and fires
 * it from the decoder's context; every other frame goes to the decoder untouched.
 */
final class CompressedKeep extends ChannelInboundHandlerAdapter {
    static final String NAME = "cult-compressed-keep";
    // A packet limiter runs inside the decoder between its inflate and its publish.
    static final String LIMITED = "backend-limiter";
    // Netty's own allocators; any other can run code between the keep's checks and its publish.
    private static final Set<Class<?>> ALLOCATORS =
            Set.of(AdaptiveByteBufAllocator.class, PooledByteBufAllocator.class, UnpooledByteBufAllocator.class);
    // A custom leak detector's trackers run their own code in every access to a tracked buffer.
    private static final boolean NETTY_LEAK_DETECTOR = nettyLeakDetector(bufferLeakDetector());
    /** Whether an allocator is exactly of one of netty's own allocator classes and buffers use netty's leak detector. */
    static final Predicate<ByteBufAllocator> NETTY_ALLOCATORS =
            alloc -> NETTY_LEAK_DETECTOR && ALLOCATORS.contains(alloc.getClass());
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

    /**
     * The backend decoder's threshold and limiter getters and its compressor's {@code inflate(ByteBuf, ByteBuf, int)},
     * each bound; {@code address} when the compressor reads its source through a memory address.
     */
    record Codec(MethodHandle threshold, MethodHandle limiter, MethodHandle inflate, boolean address) {}

    private final CompressedForwarder forwarder;
    private final Predicate<ByteBufAllocator> trustedAllocators;
    private @Nullable ChannelHandlerContext keepContext, decoderContext;
    private @Nullable MessageToMessageDecoder<?> decoder;
    private @Nullable Codec codec;

    CompressedKeep(CompressedForwarder forwarder) {
        this(forwarder, NETTY_ALLOCATORS);
    }

    /** A keep that holds frames only while {@code trustedAllocators} accepts the backend channel's allocator. */
    CompressedKeep(CompressedForwarder forwarder, Predicate<ByteBufAllocator> trustedAllocators) {
        this.forwarder = forwarder;
        this.trustedAllocators = trustedAllocators;
    }

    private static @Nullable VarHandle flag(String name) {
        try {
            return MethodHandles.privateLookupIn(MessageToMessageDecoder.class, MethodHandles.lookup())
                    .findVarHandle(MessageToMessageDecoder.class, name, boolean.class);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            return null;
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

    /** Whether this netty lets the keep set the flags a decoder's read completion reads. */
    static boolean supported() {
        return DECODE_CALLED != null && MESSAGE_PRODUCED != null;
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
     * Velocity's Java inflater requires, a memory address when the compressor reads through one, and a claim the
     * forwarder could send; else -1.
     */
    private int claimed(ByteBuf frame, Codec codec, int start, int end) {
        if (frame.nioBufferCount() != 1) return -1;
        // Velocity copies a frame without an address for libdeflate between its own two allocations.
        if (codec.address() && !frame.hasMemoryAddress()) return -1;
        int n = CompressedForwarder.dataLength(frame, start, end);
        // MAX_FORWARDED_LENGTH is below both of Velocity's uncompressed caps.
        return n > 0 && n >= forwarder.threshold() && n <= CompressedForwarder.MAX_FORWARDED_LENGTH ? n : -1;
    }

    /**
     * Whether the channel's allocator passes {@link #trustedAllocators}. Netty's own run no other code in their calls,
     * except the log and the leak listener when they report a leak.
     */
    private boolean trusted(ChannelHandlerContext ctx) {
        return trustedAllocators.test(ctx.alloc());
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
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
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
        if (ctx != keepContext || codec == null || decoder == null || !adjacent()) {
            try {
                forwarder.unlinkDetached(this);
            } finally {
                ctx.fireChannelRead(msg);
            }
            return;
        }
        ByteBuf client = null;
        ByteBuf inflated = null;
        ByteBuf copy = null;
        int start = frame.readerIndex();
        int end = frame.writerIndex();
        int n = trusted(ctx) ? claimed(frame, codec, start, end) : -1;
        int body = -1;
        if (n > 0) {
            // Velocity's decoder reads the claim and retains its input before its first allocation.
            body = start + CompressedForwarder.dataLengthSize(frame, start, end);
            frame.readerIndex(body);
            frame.retain();
            client =
                    forwarder.frameBuffer(ctx.alloc(), CompressedForwarder.varIntSize(n) + end - body, codec.address());
            if (client != null) inflated = allocate(ctx, n, true);
            if (inflated != null) copy = allocate(ctx, n, false);
        }
        // A leak report in these allocations runs code that can change the pipeline, the limiter or the frame.
        boolean adjacent = adjacent();
        boolean limited = adjacent && limited(codec.limiter());
        int consumed = copy != null && adjacent && !limited && trusted(ctx) && unchanged(frame, codec, body, end)
                ? inflate(codec, frame, body, end - body, n, client, inflated, copy)
                : -1;
        if (consumed < 0) {
            Throwable failure = CompressedForwarder.release(client, null);
            failure = CompressedForwarder.release(inflated, failure);
            failure = CompressedForwarder.release(copy, failure);
            if (n > 0) restore(frame, start, body);
            try {
                if (!adjacent) forwarder.unlinkDetached(this);
                else if (limited) forwarder.unlinkDetached(this, LIMITED);
            } finally {
                ctx.fireChannelRead(msg);
            }
            CompressedForwarder.rethrow(failure);
            return;
        }
        publish(frame, body + consumed, decoder, inflated, copy, client);
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

    /** Moves an unread {@code frame}'s reader back to {@code start} and drops the keep's reference. */
    private static void restore(ByteBuf frame, int start, int body) {
        try {
            if (frame.readerIndex() == body) frame.readerIndex(start);
            frame.release();
        } catch (IllegalReferenceCountException released) {
            // A callback released the keep's reference; the decoder refuses the freed frame.
        }
    }

    /**
     * Publishes {@code inflated}, its indices, {@code copy} and {@code client}, moves the backend's {@code frame} to
     * {@code reader}, releases it and fires {@code inflated} from the decoder's context, as the decoder's own read does;
     * restores the previous mark.
     */
    private void publish(
            ByteBuf frame,
            int reader,
            MessageToMessageDecoder<?> decoder,
            ByteBuf inflated,
            ByteBuf copy,
            ByteBuf client) {
        CompressedForwarder target = forwarder;
        ByteBuf previous = target.markBuf;
        int previousReader = target.markReader();
        int previousWriter = target.markWriter();
        ByteBuf previousCopy = target.markCopy();
        ByteBuf previousFrame = target.markFrame();
        Throwable released;
        target.mark(inflated, inflated.readerIndex(), inflated.writerIndex(), copy, client);
        try {
            DECODE_CALLED.set(decoder, true);
            MESSAGE_PRODUCED.set(decoder, true);
            frame.readerIndex(Math.min(reader, frame.writerIndex()));
            // The keep's reference goes first, as the decoder's retained input does; the input's failure wins.
            Throwable kept = CompressedForwarder.release(frame, null);
            Throwable input = CompressedForwarder.release(frame, null);
            released = input != null ? input : kept;
            decoderContext.fireChannelRead(inflated);
        } finally {
            boolean untaken = target.markFrame() == client;
            target.mark(previous, previousReader, previousWriter, previousCopy, previousFrame);
            Throwable failure = CompressedForwarder.release(copy, null);
            if (untaken) failure = CompressedForwarder.release(client, failure);
            CompressedForwarder.rethrow(failure);
        }
        // A failed release of the input surfaces after the fire, wrapped as MessageToMessageDecoder wraps it.
        CompressedForwarder.rethrow(
                released instanceof RuntimeException failure && !(failure instanceof DecoderException)
                        ? new DecoderException(failure)
                        : released);
    }

    /**
     * A new buffer of {@code n} bytes: when {@code direct} a direct one, as Velocity's decoder allocates its output for
     * either compressor, else one for the comparison copy; null when it cannot be allocated.
     */
    private static @Nullable ByteBuf allocate(ChannelHandlerContext ctx, int n, boolean direct) {
        try {
            return direct ? ctx.alloc().directBuffer(n) : ctx.alloc().buffer(n, n);
        } catch (Throwable failure) {
            return null;
        }
    }

    /**
     * Frames the claim {@code n} and the {@code length} body bytes of {@code frame} at {@code body} into {@code client},
     * inflates that body into {@code inflated} with the decoder's compressor as Velocity's decoder does, checks what it
     * checks and copies the result into {@code copy}; returns the body bytes the decoder's read consumes, or -1 when a
     * step fails or a check does not pass.
     */
    private static int inflate(
            Codec codec, ByteBuf frame, int body, int length, int n, ByteBuf client, ByteBuf inflated, ByteBuf copy) {
        int threshold;
        try {
            threshold = (int) codec.threshold().invokeExact();
        } catch (Throwable failure) {
            return -1;
        }
        if (n < threshold) return -1;
        // From the frame's first byte read to the publish only netty's buffers and Velocity's own compressor run.
        try {
            CompressedForwarder.fillFrame(client, n, frame, body, length);
            ByteBuf source = client.slice(client.writerIndex() - length, length);
            codec.inflate().invokeExact(source, inflated, n);
            if (inflated.writerIndex() != n) return -1;
            copy.writeBytes(inflated, inflated.readerIndex(), n);
            return source.readerIndex();
        } catch (Throwable failure) {
            // The decoder inflates the frame again and raises its own exception.
            return -1;
        }
    }
}
