package ac.cult.cultac.platform.velocity;

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
    /** Whether an allocator is exactly of one of netty's own allocator classes. */
    static final Predicate<ByteBufAllocator> NETTY_ALLOCATORS = alloc -> ALLOCATORS.contains(alloc.getClass());
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
    // Set by a successful inflate and read before any other code runs: the frame bytes the decoder's read consumes.
    private int consumed;

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
     * Whether {@code frame} has one NIO buffer, as Velocity's Java inflater requires, and is compressed with a data
     * length the forwarder could send.
     */
    private boolean held(ByteBuf frame) {
        if (frame.nioBufferCount() != 1) return false;
        int n = CompressedForwarder.dataLength(frame, frame.readerIndex(), frame.writerIndex());
        return n > 0 && n >= forwarder.threshold() && n <= CompressedForwarder.MAX_FORWARDED_LENGTH;
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
        int reader = 0;
        if (held(frame) && trusted(ctx)) {
            client = forwarder.frame(ctx.alloc(), frame, codec.address());
            if (client != null) inflated = inflate(ctx, codec, client, frame.readableBytes());
            if (inflated != null) {
                // Velocity's libdeflate path inflates a frame without a memory address from a copy that reads it all.
                reader = codec.address() && !frame.hasMemoryAddress()
                        ? frame.writerIndex()
                        : frame.readerIndex() + consumed;
                copy = copy(ctx, inflated);
            }
        }
        // A leak report in these allocations runs log and listener code, which can change the pipeline or the limiter.
        boolean adjacent = adjacent();
        boolean limited = adjacent && limited(codec.limiter());
        if (copy == null || !adjacent || limited || !trusted(ctx)) {
            Throwable failure = CompressedForwarder.release(client, null);
            failure = CompressedForwarder.release(inflated, failure);
            failure = CompressedForwarder.release(copy, failure);
            try {
                if (!adjacent) forwarder.unlinkDetached(this);
                else if (limited) forwarder.unlinkDetached(this, LIMITED);
            } finally {
                ctx.fireChannelRead(msg);
            }
            CompressedForwarder.rethrow(failure);
            return;
        }
        publish(frame, reader, decoder, inflated, copy, client);
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
            released = CompressedForwarder.release(frame, null);
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
     * The backend frame at the end of {@code client}, {@code length} bytes, inflated by the decoder's compressor into a
     * direct buffer of its claimed length, as Velocity's decoder inflates it, or null when any step fails or a check
     * the decoder makes does not pass; on success {@link #consumed} holds the frame bytes the decoder's read consumes.
     */
    private @Nullable ByteBuf inflate(ChannelHandlerContext ctx, Codec codec, ByteBuf client, int length) {
        int end = client.writerIndex();
        int start = end - length;
        int n = CompressedForwarder.dataLength(client, start, end);
        int body = start + CompressedForwarder.dataLengthSize(client, start, end);
        int threshold;
        try {
            threshold = (int) codec.threshold().invokeExact();
        } catch (Throwable failure) {
            return null;
        }
        // MAX_FORWARDED_LENGTH is below both of Velocity's uncompressed caps.
        if (n <= 0 || n < threshold || n > CompressedForwarder.MAX_FORWARDED_LENGTH) return null;
        if (codec.address() && !client.hasMemoryAddress()) return null;
        ByteBuf out = null;
        try {
            ByteBuf source = client.slice(body, end - body);
            out = ctx.alloc().directBuffer(n);
            codec.inflate().invokeExact(source, out, n);
            if (out.writerIndex() == n) {
                consumed = body - start + source.readerIndex();
                return out;
            }
        } catch (Throwable failure) {
            // The decoder inflates the frame again and raises its own exception.
        }
        CompressedForwarder.release(out, null);
        return null;
    }

    /** The readable bytes of {@code inflated} in a new buffer, or null when it cannot be allocated. */
    private static @Nullable ByteBuf copy(ChannelHandlerContext ctx, ByteBuf inflated) {
        int n = inflated.readableBytes();
        ByteBuf copy = null;
        try {
            copy = ctx.alloc().buffer(n, n);
            copy.writeBytes(inflated, inflated.readerIndex(), n);
            return copy;
        } catch (Throwable failure) {
            CompressedForwarder.release(copy, null);
            return null;
        }
    }
}
