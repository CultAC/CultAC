package ac.cult.cultac.platform.velocity;

import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Locale;
import java.util.concurrent.RejectedExecutionException;
import org.jetbrains.annotations.Nullable;

/**
 * Sits on the client channel directly tail-side of Velocity's compression encoder. A frame that is the inflated buffer
 * the linked backend read is delivering, unchanged, leaves as that backend's compressed frame; a frame below the
 * threshold is framed uncompressed. Both are written through the encoder's context, so only the encoder is skipped.
 * Every other frame passes on to the encoder. Confined to the client's event loop.
 */
final class CompressedForwarder extends ChannelOutboundHandlerAdapter {
    static final String NAME = "cult-compressed-forward";
    static final String COMPRESSION_ENCODER = "compression-encoder";
    private static final int MAX_FRAME_LENGTH = (1 << 21) - 1;
    // Netty follows this link from a context to the next outbound handler toward the head.
    private static final @Nullable VarHandle PREVIOUS = previous();

    private final MethodHandle encoderThreshold;
    private final int threshold;
    private final boolean guard;
    private final boolean small;
    private final boolean heap;
    private ChannelHandlerContext compression;

    // The live mark: set only inside a linked backend's channelRead, restored in its finally.
    ByteBuf markBuf;
    private int markReader, markWriter;
    private ByteBuf keptBuf;
    private int keptStart, keptEnd;
    private @Nullable ByteBuf markCopy;

    boolean disabled;
    private String status = "on";
    private String linkStatus = "none";
    private Channel linkChannel;
    private CompressedKeep linkKeep;
    private CompressedMark linkMark;

    private long forwarded, forwardedWireBytes, forwardedInflatedBytes;
    private long notMarked, indicesChanged, guardMismatch, belowThreshold, framedSmall, failures, forwardFailures;

    /** @param encoderThreshold reads the client encoder's current threshold, as {@code ()int} */
    CompressedForwarder(MethodHandle encoderThreshold, boolean guard, boolean small, boolean heap) {
        this.encoderThreshold = encoderThreshold;
        this.threshold = encoderThreshold();
        this.guard = guard;
        this.small = small;
        this.heap = heap;
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

    /** Whether a write from {@code ctx} reaches {@code encoder} with no handler between them. */
    private static boolean follows(ChannelHandlerContext ctx, ChannelHandlerContext encoder) {
        return PREVIOUS != null && PREVIOUS.getVolatile(ctx) == encoder;
    }

    /** The encoder's threshold when this forwarder was installed. */
    int threshold() {
        return threshold;
    }

    boolean guard() {
        return guard;
    }

    private int encoderThreshold() {
        try {
            return (int) encoderThreshold.invokeExact();
        } catch (Throwable failure) {
            return Integer.MIN_VALUE;
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        compression = ctx.pipeline().context(COMPRESSION_ENCODER);
        if (compression == null) disable("no-compression");
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        disabled = true;
        status = "removed";
        clearMark();
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
        if (target.isRemoved()) {
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
            out = forwardedFrame(ctx, b, n);
        } catch (Throwable failure) {
            failures++;
            forwardFailures++;
            out = null;
        }
        if (out == null) {
            ctx.write(msg, promise);
            return;
        }
        b.skipBytes(n);
        b.release();
        target.write(out, promise);
    }

    /** {@code varint(L)} and the backend's frame of length L, or null (counted) when the encoder must compress. */
    private ByteBuf forwardedFrame(ChannelHandlerContext ctx, ByteBuf b, int n) {
        ByteBuf kept = keptBuf;
        int start = keptStart;
        int end = keptEnd;
        int length = end - start;
        if (b.readerIndex() != markReader
                || b.writerIndex() != markWriter
                || length <= 0
                || length > MAX_FRAME_LENGTH
                || dataLength(kept, start, end) != n) {
            indicesChanged++;
            return null;
        }
        if (guard && !sameContent(b, n)) {
            guardMismatch++;
            return null;
        }
        int size = varIntSize(length) + length;
        ByteBuf out = heap ? ctx.alloc().heapBuffer(size) : ctx.alloc().directBuffer(size);
        try {
            Wire.writeVarInt(out, length);
            int at = out.writerIndex();
            kept.getBytes(start, out, at, length);
            out.writerIndex(at + length);
        } catch (Throwable failure) {
            out.release();
            throw failure;
        }
        forwarded++;
        forwardedWireBytes += size;
        forwardedInflatedBytes += n;
        return out;
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
        framedSmall++;
        b.skipBytes(n);
        b.release();
        target.write(out, promise);
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

    /** Whether the {@code n} bytes leaving now equal the copy the mark took when the backend's frame was inflated. */
    private boolean sameContent(ByteBuf b, int n) {
        ByteBuf copy = markCopy;
        if (copy == null || copy.readableBytes() != n) return false;
        // A leak-tracking wrapper records every accessor call: one view per buffer, not n / 8 getLong calls.
        if (b.nioBufferCount() == 1 && copy.nioBufferCount() == 1)
            return b.internalNioBuffer(b.readerIndex(), n).equals(copy.internalNioBuffer(copy.readerIndex(), n));
        return ByteBufUtil.equals(b, b.readerIndex(), copy, copy.readerIndex(), n);
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

    void mark(ByteBuf buf, int reader, int writer, ByteBuf kept, int start, int end, @Nullable ByteBuf copy) {
        markBuf = buf;
        markReader = reader;
        markWriter = writer;
        keptBuf = kept;
        keptStart = start;
        keptEnd = end;
        markCopy = copy;
    }

    int markReader() {
        return markReader;
    }

    int markWriter() {
        return markWriter;
    }

    ByteBuf keptBuf() {
        return keptBuf;
    }

    int keptStart() {
        return keptStart;
    }

    int keptEnd() {
        return keptEnd;
    }

    @Nullable
    ByteBuf markCopy() {
        return markCopy;
    }

    private void clearMark() {
        mark(null, 0, 0, null, 0, 0, null);
    }

    void disable(String why) {
        if (disabled) return;
        disabled = true;
        status = why;
        clearMark();
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
        if (pipeline.get(CompressedKeep.NAME) == keep) pipeline.remove(keep);
        if (pipeline.get(CompressedMark.NAME) == mark) pipeline.remove(mark);
    }

    String stats() {
        long recompressed = notMarked + indicesChanged + guardMismatch + forwardFailures;
        long eligible = forwarded + recompressed;
        String hit = eligible == 0 ? "n/a" : String.format(Locale.ROOT, "%.2f%%", 100.0 * forwarded / eligible);
        return "status=" + status + ", link=" + linkStatus + ", threshold=" + threshold + ", guard="
                + (guard ? "on" : "off") + ", hit=" + hit + ", forwarded=" + forwarded + " (" + forwardedWireBytes
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
