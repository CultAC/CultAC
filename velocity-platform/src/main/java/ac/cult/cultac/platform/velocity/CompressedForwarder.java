package ac.cult.cultac.platform.velocity;

import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.EncoderException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

/**
 * Sits on the client channel directly after Velocity's compression encoder and sends the marked, unchanged inflated
 * buffer as its keep's private frame through the encoder's context. Every other message passes on to the encoder with
 * no call of its own made. Confined to the client's event loop.
 */
final class CompressedForwarder extends ChannelOutboundHandlerAdapter {
    static final String NAME = "cult-compressed-forward";
    static final String COMPRESSION_ENCODER = "compression-encoder";
    // Leak detection is enabled, or the client's allocator or netty's leak detector is not of netty's own classes.
    static final String UNTRUSTED = "client-untrusted";
    private static final int MAX_FRAME_LENGTH = (1 << 21) - 1;
    // Velocity's encoder fails a write whose deflate output is this long or longer.
    private static final int MAX_COMPRESSED_LENGTH = 1 << 21;
    // Netty's largest live reference count; Velocity's encoder retains a compatible input once before it deflates.
    static final int MAX_REFERENCES = Integer.MAX_VALUE >>> 1;
    /** 1,838,585: the longest inflated frame whose {@link #deflateBound} is below Velocity's compressed limit. */
    static final int MAX_FORWARDED_LENGTH = maxForwardedLength();
    // Netty follows this link from a context to the next outbound handler toward the head.
    private static final @Nullable VarHandle PREVIOUS = link("prev");
    // A context removed from another thread keeps its own links until its removal runs on the loop.
    private static final @Nullable VarHandle NEXT = link("next");

    private final MethodHandle encoderThreshold;
    private final int threshold;
    private final boolean heap;
    private final boolean consume;
    private final MethodHandle disposed;
    private final Predicate<ByteBufAllocator> trustedAllocators;
    private final Runnable untrusted;
    private @Nullable ChannelHandlerContext compression;
    private final ChannelHandlerContext inbound;

    // The live mark: set only inside a linked backend's channelRead, restored in its finally.
    ByteBuf markBuf;
    private int markReader, markWriter;
    private @Nullable ByteBuf markCopy;
    private @Nullable ByteBuf markFrame;

    boolean disabled;
    private String status = "on";
    private String linkStatus = "none";
    private Channel linkChannel;
    private CompressedKeep linkKeep;

    private long forwarded, forwardedWireBytes, forwardedInflatedBytes;
    private long notMarked, indicesChanged, guardMismatch, failures, forwardFailures;

    /**
     * {@code encoderThreshold} reads {@code compression}'s threshold as {@code ()int}; {@code inbound} is the client's
     * compression decoder, which shares the encoder's compressor; {@code disposed} reads that compressor's closed flag
     * as {@code ()boolean}; a marked buffer is forwarded only while {@code trustedAllocators} accepts the client
     * channel's allocator, and {@code untrusted} runs when it refuses.
     */
    CompressedForwarder(
            MethodHandle encoderThreshold,
            ChannelHandlerContext compression,
            ChannelHandlerContext inbound,
            boolean heap,
            boolean consume,
            MethodHandle disposed,
            Predicate<ByteBufAllocator> trustedAllocators,
            Runnable untrusted) {
        this.encoderThreshold = encoderThreshold;
        this.trustedAllocators = trustedAllocators;
        this.untrusted = untrusted;
        this.threshold = encoderThreshold();
        this.compression = compression;
        this.inbound = inbound;
        this.heap = heap;
        this.consume = consume;
        this.disposed = disposed;
    }

    /** An upper bound on either compressor's zlib output for {@code n} bytes, a fast level's n/8 expansion included. */
    static int deflateBound(int n) {
        return n + (n >> 3) + (n >> 6) + 16;
    }

    private static int maxForwardedLength() {
        int n = MAX_COMPRESSED_LENGTH;
        while (deflateBound(n) >= MAX_COMPRESSED_LENGTH) n--;
        return n;
    }

    private static @Nullable VarHandle link(String name) {
        try {
            Class<?> context = Class.forName(
                    "io.netty.channel.AbstractChannelHandlerContext",
                    false,
                    ChannelHandlerContext.class.getClassLoader());
            return MethodHandles.privateLookupIn(context, MethodHandles.lookup())
                    .findVarHandle(context, name, context);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            return null;
        }
    }

    /** Whether this netty lets a handler read which context its writes reach next. */
    static boolean supported() {
        return PREVIOUS != null && NEXT != null;
    }

    /**
     * Whether {@code before} is the context directly head-side of {@code ctx} and links to it, with no handler between
     * them.
     */
    static boolean follows(ChannelHandlerContext ctx, ChannelHandlerContext before) {
        return PREVIOUS != null
                && NEXT != null
                && PREVIOUS.getVolatile(ctx) == before
                && NEXT.getVolatile(before) == ctx;
    }

    /** The encoder's threshold read at install. */
    int threshold() {
        return threshold;
    }

    private int encoderThreshold() {
        try {
            return (int) encoderThreshold.invokeExact();
        } catch (Throwable failure) {
            return Integer.MIN_VALUE;
        }
    }

    /** Whether the client's compressor is closed, or its flag cannot be read. */
    private boolean compressorClosed() {
        try {
            return (boolean) disposed.invokeExact();
        } catch (Throwable failure) {
            return true;
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        disabled = true;
        status = "removed";
        compression = null;
        unlink();
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        ChannelHandlerContext target = compression;
        // Object identity alone decides: a message other than the published buffer gets no call before it passes on.
        if (msg != markBuf || disabled || target == null) {
            if (!disabled && target != null && msg instanceof ByteBuf) notMarked++;
            ctx.write(msg, promise);
            return;
        }
        ByteBuf b = markBuf;
        if (target.isRemoved() || inbound.isRemoved()) {
            disable("compression-removed");
            ctx.write(msg, promise);
            return;
        }
        if (!follows(ctx, target)) {
            disable("adjacency");
            ctx.write(msg, promise);
            return;
        }
        if (encoderThreshold() != threshold) {
            disable("threshold");
            ctx.write(msg, promise);
            return;
        }
        if (!trustedAllocators.test(ctx.alloc())) {
            try {
                disable(UNTRUSTED);
                untrusted.run();
            } finally {
                ctx.write(msg, promise);
            }
            return;
        }
        int n = b.readableBytes();
        if (compressorClosed()) {
            disable("compression-closed");
            ctx.write(msg, promise);
            return;
        }
        if (b.refCnt() >= MAX_REFERENCES) {
            ctx.write(msg, promise);
            return;
        }
        ByteBuf out;
        try {
            out = forwardedFrame(b, n);
        } catch (Throwable failure) {
            failures++;
            forwardFailures++;
            out = null;
        }
        if (out == null) {
            ctx.write(msg, promise);
            return;
        }
        try {
            if (consume) b.skipBytes(n);
            b.release();
        } catch (Throwable failure) {
            out.release();
            throw encoderFailure(failure);
        }
        int sent = out.readableBytes();
        target.write(out, promise);
        forwarded++;
        forwardedWireBytes += sent;
        forwardedInflatedBytes += n;
    }

    /** As Velocity's encoder fails a write: an {@link EncoderException} as is, any other throwable wrapped in one. */
    private static EncoderException encoderFailure(Throwable failure) {
        return failure instanceof EncoderException encoder ? encoder : new EncoderException(failure);
    }

    /** Takes the frame the mark publishes for {@code b}, or returns null (counted) when the encoder must compress. */
    private @Nullable ByteBuf forwardedFrame(ByteBuf b, int n) {
        ByteBuf frame = markFrame;
        if (frame == null || b.readerIndex() != markReader || b.writerIndex() != markWriter) {
            indicesChanged++;
            return null;
        }
        if (!sameContent(b, n)) {
            guardMismatch++;
            return null;
        }
        markFrame = null;
        markBuf = null;
        return frame;
    }

    /**
     * A new empty buffer for the private frame of a backend frame of {@code length} readable bytes, of the cipher's kind
     * or direct when {@code direct}, or null.
     */
    @Nullable
    ByteBuf frameBuffer(ByteBufAllocator alloc, int length, boolean direct) {
        if (length > MAX_FRAME_LENGTH) return null;
        int size = varIntSize(length) + length;
        try {
            return heap && !direct ? alloc.heapBuffer(size) : alloc.directBuffer(size);
        } catch (Throwable failure) {
            failures++;
            return null;
        }
    }

    /**
     * Writes a compressed frame claiming {@code n} bytes: its length, {@code varint(n)} and the {@code length} body
     * bytes of the backend's {@code frame} at {@code body}.
     */
    static void fillFrame(ByteBuf out, int n, ByteBuf frame, int body, int length) {
        Wire.writeVarInt(out, varIntSize(n) + length);
        Wire.writeVarInt(out, n);
        out.writeBytes(frame, body, length);
    }

    /** Releases a non-null {@code buf}; returns {@code failure}, or what the release threw when that is null. */
    static @Nullable Throwable release(@Nullable ByteBuf buf, @Nullable Throwable failure) {
        if (buf == null) return failure;
        try {
            buf.release();
        } catch (RuntimeException | Error thrown) {
            if (failure == null) return thrown;
            failure.addSuppressed(thrown);
        }
        return failure;
    }

    /** Throws {@code failure}, an unchecked throwable from {@link #release}, when it is not null. */
    static void rethrow(@Nullable Throwable failure) {
        if (failure instanceof RuntimeException exception) throw exception;
        if (failure instanceof Error error) throw error;
    }

    /** The data length VarInt at the start of a compressed frame, or -1 when it is malformed. */
    static int dataLength(ByteBuf frame, int start, int end) {
        int value = 0;
        for (int i = 0; i < 5 && start + i < end; i++) {
            int next = frame.getUnsignedByte(start + i);
            value |= (next & 0x7f) << (i * 7);
            if ((next & 0x80) == 0) return value;
        }
        return -1;
    }

    /** How many bytes the data length VarInt at the start of a compressed frame takes, or -1 when it is malformed. */
    static int dataLengthSize(ByteBuf frame, int start, int end) {
        for (int i = 0; i < 5 && start + i < end; i++) if ((frame.getUnsignedByte(start + i) & 0x80) == 0) return i + 1;
        return -1;
    }

    /** Whether the {@code n} bytes of {@code b} equal the mark's copy of the inflated buffer, byte for byte. */
    private boolean sameContent(ByteBuf b, int n) {
        ByteBuf copy = markCopy;
        if (copy == null || copy.readableBytes() != n) return false;
        if (b.nioBufferCount() != 1 || copy.nioBufferCount() != 1) return false;
        // A leak-tracking wrapper records a trace per accessor call.
        return b.internalNioBuffer(b.readerIndex(), n).equals(copy.internalNioBuffer(copy.readerIndex(), n));
    }

    static int varIntSize(int value) {
        int size = 1;
        while ((value & ~0x7f) != 0) {
            size++;
            value >>>= 7;
        }
        return size;
    }

    /** Whether this handler directly follows the compression encoder it writes through. */
    static boolean adjacent(ChannelPipeline pipeline) {
        var names = pipeline.names();
        int at = names.indexOf(COMPRESSION_ENCODER);
        return at >= 0 && at + 1 < names.size() && NAME.equals(names.get(at + 1));
    }

    /** Publishes the mark; a non-null {@code frame} passes to the first forward of {@code buf} that takes it. */
    void mark(ByteBuf buf, int reader, int writer, @Nullable ByteBuf copy, @Nullable ByteBuf frame) {
        markBuf = buf;
        markReader = reader;
        markWriter = writer;
        markCopy = copy;
        markFrame = frame;
    }

    int markReader() {
        return markReader;
    }

    int markWriter() {
        return markWriter;
    }

    @Nullable
    ByteBuf markCopy() {
        return markCopy;
    }

    /** The marked frame not yet taken by a forward. */
    @Nullable
    ByteBuf markFrame() {
        return markFrame;
    }

    void disable(String why) {
        if (disabled) return;
        disabled = true;
        status = why;
        unlink();
    }

    void linkStatus(String status) {
        linkStatus = status;
    }

    boolean linkedTo(Channel backend) {
        return linkChannel == backend && backend.pipeline().get(CompressedKeep.NAME) == linkKeep;
    }

    void link(Channel backend, CompressedKeep keep) {
        linkChannel = backend;
        linkKeep = keep;
        linkStatus = "linked";
    }

    /** Removes the current link with status {@code backend-adjacency} when {@code keep} is its keep. */
    void unlinkDetached(CompressedKeep keep) {
        unlinkDetached(keep, "backend-adjacency");
    }

    /** Removes the current link with {@code status} when {@code keep} is its keep. */
    void unlinkDetached(CompressedKeep keep, String status) {
        if (linkKeep != keep) return;
        try {
            unlink();
        } finally {
            linkStatus = status;
        }
    }

    /** Removes the current link's keep from its backend; never waits for another loop. */
    void unlink() {
        Channel channel = linkChannel;
        CompressedKeep keep = linkKeep;
        linkChannel = null;
        linkKeep = null;
        if (channel == null) return;
        linkStatus = "none";
        if (channel.eventLoop().inEventLoop()) {
            remove(channel.pipeline(), keep);
            return;
        }
        try {
            channel.eventLoop().execute(() -> remove(channel.pipeline(), keep));
        } catch (RejectedExecutionException closed) {
            // A closed loop's channel is deregistered, its handlers removed.
        }
    }

    /** Removes {@code handler} from {@code pipeline}; a handler already gone counts as removed. */
    static void remove(ChannelPipeline pipeline, ChannelHandler handler) {
        try {
            pipeline.remove(handler);
        } catch (NoSuchElementException gone) {
            // A handler another path removed first leaves nothing to remove.
        }
    }

    /**
     * The status, link and counters; a link whose backend channel is inactive reads {@code backend-closed}. The hit
     * rate is over writes of a marked buffer; {@code not marked} counts every other buffer written while enabled.
     */
    String stats() {
        long recompressed = indicesChanged + guardMismatch + forwardFailures;
        long eligible = forwarded + recompressed;
        String hit = eligible == 0 ? "n/a" : String.format(Locale.ROOT, "%.2f%%", 100.0 * forwarded / eligible);
        Channel backend = linkChannel;
        String link = backend != null && !backend.isActive() ? "backend-closed" : linkStatus;
        return "status=" + status + ", link=" + link + ", threshold=" + threshold + ", hit=" + hit
                + ", forwarded=" + forwarded + " (" + forwardedWireBytes
                + " B sent, " + forwardedInflatedBytes + " B inflated), not marked=" + notMarked
                + "; recompressed: indices changed=" + indicesChanged + ", guard mismatch=" + guardMismatch
                + "; failures=" + failures;
    }

    void resetStats() {
        forwarded = forwardedWireBytes = forwardedInflatedBytes = 0;
        notMarked = indicesChanged = guardMismatch = failures = forwardFailures = 0;
    }

    long forwarded() {
        return forwarded;
    }

    long notMarked() {
        return notMarked;
    }

    long indicesChanged() {
        return indicesChanged;
    }

    long guardMismatch() {
        return guardMismatch;
    }

    String status() {
        return status;
    }

    String linkStatus() {
        return linkStatus;
    }
}
