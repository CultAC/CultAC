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
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.concurrent.RejectedExecutionException;
import org.jetbrains.annotations.Nullable;

/**
 * Sits on the client channel directly after Velocity's compression encoder and sends a marked, unchanged inflated
 * buffer as the private frame its keep built, or frames a small one uncompressed, through the encoder's context.
 * Every other frame passes on to the encoder. Confined to the client's event loop.
 */
final class CompressedForwarder extends ChannelOutboundHandlerAdapter {
    static final String NAME = "cult-compressed-forward";
    static final String COMPRESSION_ENCODER = "compression-encoder";
    private static final int MAX_FRAME_LENGTH = (1 << 21) - 1;
    // Velocity's encoder fails a write whose deflate output is this long or longer.
    private static final int MAX_COMPRESSED_LENGTH = 1 << 21;
    /** 1,838,585: the longest inflated frame whose {@link #deflateBound} is below Velocity's compressed limit. */
    static final int MAX_FORWARDED_LENGTH = maxForwardedLength();
    // Netty follows this link from a context to the next outbound handler toward the head.
    private static final @Nullable VarHandle PREVIOUS = previous();

    private final MethodHandle encoderThreshold;
    private final int threshold;
    private final boolean small;
    private final boolean heap;
    private final boolean consume;
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
    private CompressedMark linkMark;

    private long forwarded, forwardedWireBytes, forwardedInflatedBytes;
    private long notMarked, indicesChanged, guardMismatch, belowThreshold, framedSmall, failures, forwardFailures;

    /**
     * {@code encoderThreshold} reads {@code compression}'s threshold as {@code ()int}; {@code inbound} is the client's
     * compression decoder, which shares the encoder's compressor.
     */
    CompressedForwarder(
            MethodHandle encoderThreshold,
            ChannelHandlerContext compression,
            ChannelHandlerContext inbound,
            boolean small,
            boolean heap,
            boolean consume) {
        this.encoderThreshold = encoderThreshold;
        this.threshold = encoderThreshold();
        this.compression = compression;
        this.inbound = inbound;
        this.small = small;
        this.heap = heap;
        this.consume = consume;
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

    private static @Nullable VarHandle previous() {
        try {
            Class<?> context = Class.forName(
                    "io.netty.channel.AbstractChannelHandlerContext",
                    false,
                    ChannelHandlerContext.class.getClassLoader());
            return MethodHandles.privateLookupIn(context, MethodHandles.lookup())
                    .findVarHandle(context, "prev", context);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            return null;
        }
    }

    /** Whether this netty lets a handler read which context its writes reach next. */
    static boolean supported() {
        return PREVIOUS != null;
    }

    /** Whether {@code before} is the context directly head-side of {@code ctx}, with no handler between them. */
    static boolean follows(ChannelHandlerContext ctx, ChannelHandlerContext before) {
        return PREVIOUS != null && PREVIOUS.getVolatile(ctx) == before;
    }

    /** The encoder's threshold when this forwarder was installed. */
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
        if (disabled || target == null || !(msg instanceof ByteBuf b)) {
            ctx.write(msg, promise);
            return;
        }
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
        int n = b.readableBytes();
        boolean marked = b == markBuf;
        if (n < threshold) {
            if (marked) belowThreshold++;
            if (small) frameSmall(ctx, target, b, n, promise);
            else ctx.write(msg, promise);
            return;
        }
        if (!marked) {
            notMarked++;
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
            throw failure;
        }
        target.write(out, promise);
    }

    /** Takes the frame the mark published for {@code b}, or returns null (counted) when the encoder must compress. */
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
        forwarded++;
        forwardedWireBytes += frame.readableBytes();
        forwardedInflatedBytes += n;
        return frame;
    }

    /** {@code varint(L)} and a copy of the L readable bytes of the backend's {@code frame} in a new buffer, or null. */
    @Nullable
    ByteBuf frame(ByteBufAllocator alloc, ByteBuf frame) {
        int length = frame.readableBytes();
        if (length > MAX_FRAME_LENGTH) return null;
        int size = varIntSize(length) + length;
        ByteBuf out = null;
        try {
            out = heap ? alloc.heapBuffer(size) : alloc.directBuffer(size);
            Wire.writeVarInt(out, length);
            out.writeBytes(frame, frame.readerIndex(), length);
            return out;
        } catch (RuntimeException | OutOfMemoryError failure) {
            failures++;
            if (out != null) out.release();
            return null;
        }
    }

    /** Whether {@code kept[start, end)} is a compressed frame of data length {@code n} that Velocity could send. */
    boolean forwardable(ByteBuf kept, int start, int end, int n) {
        int length = end - start;
        return n <= MAX_FORWARDED_LENGTH
                && length > 0
                && length <= MAX_FRAME_LENGTH
                && dataLength(kept, start, end) == n;
    }

    private void frameSmall(
            ChannelHandlerContext ctx, ChannelHandlerContext target, ByteBuf b, int n, ChannelPromise promise) {
        ByteBuf out = null;
        try {
            int length = n + 1;
            int size = varIntSize(length) + length;
            out = heap ? ctx.alloc().heapBuffer(size) : ctx.alloc().directBuffer(size);
            Wire.writeVarInt(out, length);
            out.writeByte(0);
            int at = out.writerIndex();
            b.getBytes(b.readerIndex(), out, at, n);
            out.writerIndex(at + n);
        } catch (Throwable failure) {
            failures++;
            if (out != null) out.release();
            ctx.write(b, promise);
            return;
        }
        try {
            b.skipBytes(n);
            b.release();
        } catch (Throwable failure) {
            out.release();
            throw failure;
        }
        framedSmall++;
        target.write(out, promise);
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
    private static int dataLength(ByteBuf frame, int start, int end) {
        int value = 0;
        for (int i = 0; i < 5 && start + i < end; i++) {
            int next = frame.getUnsignedByte(start + i);
            value |= (next & 0x7f) << (i * 7);
            if ((next & 0x80) == 0) return value;
        }
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

    void link(Channel backend, CompressedKeep keep, CompressedMark mark) {
        linkChannel = backend;
        linkKeep = keep;
        linkMark = mark;
        linkStatus = "linked";
    }

    /** Removes the current link with status {@code backend-adjacency} when {@code keep} is its keep. */
    void unlinkDetached(CompressedKeep keep) {
        if (linkKeep != keep) return;
        try {
            unlink();
        } finally {
            linkStatus = "backend-adjacency";
        }
    }

    /** Removes the current link's handlers from its backend; never waits for another loop. */
    void unlink() {
        Channel channel = linkChannel;
        CompressedKeep keep = linkKeep;
        CompressedMark mark = linkMark;
        linkChannel = null;
        linkKeep = null;
        linkMark = null;
        if (channel == null) return;
        if (!disabled) linkStatus = "none";
        if (channel.eventLoop().inEventLoop()) {
            removeLink(channel.pipeline(), keep, mark);
            return;
        }
        try {
            channel.eventLoop().execute(() -> removeLink(channel.pipeline(), keep, mark));
        } catch (RejectedExecutionException closed) {
            // A closed loop has deregistered the channel and removed its handlers.
        }
    }

    private static void removeLink(ChannelPipeline pipeline, CompressedKeep keep, CompressedMark mark) {
        try {
            remove(pipeline, keep);
        } finally {
            remove(pipeline, mark);
        }
    }

    /** Removes {@code handler} from {@code pipeline}; a handler already gone counts as removed. */
    static void remove(ChannelPipeline pipeline, ChannelHandler handler) {
        try {
            pipeline.remove(handler);
        } catch (NoSuchElementException gone) {
            // Already removed.
        }
    }

    String stats() {
        long recompressed = notMarked + indicesChanged + guardMismatch + forwardFailures;
        long eligible = forwarded + recompressed;
        String hit = eligible == 0 ? "n/a" : String.format(Locale.ROOT, "%.2f%%", 100.0 * forwarded / eligible);
        return "status=" + status + ", link=" + linkStatus + ", threshold=" + threshold + ", hit=" + hit
                + ", forwarded=" + forwarded + " (" + forwardedWireBytes
                + " B sent, " + forwardedInflatedBytes + " B inflated), recompressed: not marked=" + notMarked
                + ", indices changed=" + indicesChanged + ", guard mismatch=" + guardMismatch
                + "; below threshold=" + belowThreshold + ", framed small=" + framedSmall + ", failures="
                + failures;
    }

    void resetStats() {
        forwarded = forwardedWireBytes = forwardedInflatedBytes = 0;
        notMarked = indicesChanged = guardMismatch = belowThreshold = framedSmall = failures = forwardFailures = 0;
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

    long belowThreshold() {
        return belowThreshold;
    }

    long framedSmall() {
        return framedSmall;
    }

    String status() {
        return status;
    }

    String linkStatus() {
        return linkStatus;
    }
}
