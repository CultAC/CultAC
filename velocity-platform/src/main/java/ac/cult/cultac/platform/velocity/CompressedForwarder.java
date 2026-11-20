package ac.cult.cultac.platform.velocity;

import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPipeline;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.EncoderException;
import io.netty.util.concurrent.EventExecutor;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

/**
 * Sits on the client channel directly after Velocity's compression encoder and sends the marked, unchanged inflated
 * buffer as its keep's private frame through the encoder's context; every other message passes on with no call of its
 * own. Runs on the loop it was installed on, takes a mark only on its publishing thread, and leaves at deregistration.
 */
final class CompressedForwarder extends ChannelDuplexHandler {
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
    // Netty 4.2.19 never links a context back into the list after it leaves.
    private static final @Nullable VarHandle NEXT = link("next");
    // Netty touches every message it dispatches to a context of a pipeline built with leak detection enabled.
    private static final @Nullable VarHandle TOUCH = touch();
    // A context added with an executor of its own keeps it when its channel is registered on another loop.
    private static final @Nullable VarHandle OWN_EXECUTOR = ownExecutorField();

    private final MethodHandle encoderThreshold;
    private final int threshold;
    private final boolean consume;
    private final MethodHandle disposed;
    private final Predicate<ByteBufAllocator> trustedAllocators;
    private final Runnable untrusted;
    private @Nullable ChannelHandlerContext compression;
    private final ChannelHandlerContext inbound;
    private final EventLoop loop;

    // The live mark: set only inside a linked backend's channelRead, restored in its finally.
    ByteBuf markBuf;
    private int markReader, markWriter;
    private @Nullable ByteBuf markCopy;
    private @Nullable ByteBuf markFrame;
    private @Nullable Thread markThread;

    boolean disabled;
    private String status = "on";
    private String linkStatus = "none";
    private Channel linkChannel;
    private CompressedKeep linkKeep;
    // An installed keep is held by its pipeline; these leave a removed keep and its backend to the collector.
    private @Nullable WeakReference<CompressedKeep> linkRef, endedKeep;

    private long forwarded, forwardedWireBytes, forwardedInflatedBytes;
    private long notMarked, indicesChanged, guardMismatch, failures, forwardFailures, decoderUnlinked;

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
        this.loop = inbound.channel().eventLoop();
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

    private static @Nullable VarHandle touch() {
        try {
            return MethodHandles.privateLookupIn(DefaultChannelPipeline.class, MethodHandles.lookup())
                    .findVarHandle(DefaultChannelPipeline.class, "touch", boolean.class);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            return null;
        }
    }

    private static @Nullable VarHandle ownExecutorField() {
        try {
            Class<?> context = Class.forName(
                    "io.netty.channel.AbstractChannelHandlerContext",
                    false,
                    ChannelHandlerContext.class.getClassLoader());
            return MethodHandles.privateLookupIn(context, MethodHandles.lookup())
                    .findVarHandle(context, "childExecutor", EventExecutor.class);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            return null;
        }
    }

    /**
     * Whether this netty lets a handler read which context its writes reach next, whether a pipeline touches the
     * messages it dispatches, and which executor a context was added with.
     */
    static boolean supported() {
        return PREVIOUS != null && NEXT != null && TOUCH != null && OWN_EXECUTOR != null;
    }

    /** The executor {@code ctx}'s handler was added with, or null when it was added with none. */
    static @Nullable EventExecutor ownExecutor(ChannelHandlerContext ctx) {
        return (EventExecutor) OWN_EXECUTOR.get(ctx);
    }

    /**
     * Whether {@code pipeline} touches every message it dispatches to a context, as netty set it when the pipeline was
     * built; true when it is not netty's pipeline class or that cannot be read.
     */
    static boolean touches(ChannelPipeline pipeline) {
        if (TOUCH == null || !(pipeline instanceof DefaultChannelPipeline built)) return true;
        try {
            return (boolean) TOUCH.get(built);
        } catch (RuntimeException unreadable) {
            return true;
        }
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

    /** Whether {@code ctx} is in its pipeline's handler list: both of its neighbours link to it. */
    static boolean linked(ChannelHandlerContext ctx) {
        if (PREVIOUS == null || NEXT == null) return false;
        Object before = PREVIOUS.getVolatile(ctx);
        Object after = NEXT.getVolatile(ctx);
        return before != null && after != null && NEXT.getVolatile(before) == ctx && PREVIOUS.getVolatile(after) == ctx;
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

    /** Leaves the pipeline, which removes the link's keep, and passes the event on unchanged. */
    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) {
        try {
            remove(ctx.pipeline(), this);
        } finally {
            ctx.fireChannelUnregistered();
        }
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
        // Defence only: a keep publishes on the client's loop, where every write of this channel runs.
        if (markThread != Thread.currentThread()) {
            ctx.write(msg, promise);
            return;
        }
        ByteBuf b = markBuf;
        if (target.isRemoved() || inbound.isRemoved()) {
            disable("compression-removed");
            ctx.write(msg, promise);
            return;
        }
        // Netty unlinks a context before its removal callback runs, and an insert beside it writes one link at a time.
        if (!linked(inbound)) {
            decoderUnlinked++;
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
     * A new empty direct buffer, the kind Velocity's encoder gives a compressed frame, for the private frame of a
     * backend frame of {@code length} readable bytes, or null.
     */
    @Nullable
    ByteBuf frameBuffer(ByteBufAllocator alloc, int length) {
        if (length > MAX_FRAME_LENGTH) return null;
        int size = varIntSize(length) + length;
        try {
            return alloc.directBuffer(size);
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

    /**
     * Whether every handler in {@code pipeline} runs on its channel's event loop; netty then passes an added handler
     * over for every event it does not handle.
     */
    static boolean onLoop(ChannelPipeline pipeline) {
        EventLoop loop = pipeline.channel().eventLoop();
        for (Map.Entry<String, ChannelHandler> entry : pipeline) {
            ChannelHandlerContext ctx = pipeline.context(entry.getKey());
            if (ctx != null && ctx.executor() != loop) return false;
        }
        return true;
    }

    /** Whether this handler directly follows the compression encoder it writes through. */
    static boolean adjacent(ChannelPipeline pipeline) {
        var names = pipeline.names();
        int at = names.indexOf(COMPRESSION_ENCODER);
        return at >= 0 && at + 1 < names.size() && NAME.equals(names.get(at + 1));
    }

    /**
     * Publishes the mark from the current thread; a non-null {@code frame} passes to the first forward of {@code buf}
     * on this thread that takes it.
     */
    void mark(ByteBuf buf, int reader, int writer, @Nullable ByteBuf copy, @Nullable ByteBuf frame) {
        markBuf = buf;
        markReader = reader;
        markWriter = writer;
        markCopy = copy;
        markFrame = frame;
        markThread = Thread.currentThread();
    }

    /**
     * Whether the client channel is registered on the loop the forwarder was installed on and the current thread is
     * that loop, the only thread a keep publishes from; a channel registered there when a read starts stays there
     * until the read ends.
     */
    boolean onClientLoop() {
        Channel client = inbound.channel();
        return client.isRegistered() && client.eventLoop() == loop && loop.inEventLoop();
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
        end();
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
        linkRef = new WeakReference<>(keep);
        linkStatus = "linked";
    }

    /** Whether {@code keep} is the current link's keep. */
    boolean linksTo(CompressedKeep keep) {
        return linkKeep == keep;
    }

    /** Ends the current link with status {@code backend-adjacency} when {@code keep} is its keep. */
    void unlinkDetached(CompressedKeep keep) {
        unlinkDetached(keep, "backend-adjacency");
    }

    /** Ends the current link with {@code status} when {@code keep} is its keep. */
    void unlinkDetached(CompressedKeep keep, String status) {
        if (linkKeep != keep) return;
        try {
            end();
        } finally {
            linkStatus = status;
        }
    }

    /**
     * As {@link #unlinkDetached(CompressedKeep, String)}, in a task on the loop the forwarder was installed on that acts
     * only while {@link #onClientLoop}; nothing the hand-off throws leaves this method.
     */
    void unlinkLater(CompressedKeep keep, String status) {
        try {
            loop.execute(() -> {
                if (onClientLoop()) unlinkDetached(keep, status);
            });
        } catch (Throwable rejected) {
            // A loop that takes no task runs no forwarder of a registered channel.
        }
    }

    /**
     * Ends the current link and leaves its keep, which passes every frame on untouched, for the next {@link #unlink};
     * makes no pipeline change and never waits.
     */
    private void end() {
        Channel channel = linkChannel;
        WeakReference<CompressedKeep> keep = linkRef;
        linkChannel = null;
        linkKeep = null;
        linkRef = null;
        if (channel == null) return;
        linkStatus = "none";
        endedKeep = keep;
    }

    /** Removes the current link's keep and an ended link's keep from their backends; never waits for another loop. */
    void unlink() {
        WeakReference<CompressedKeep> ended = endedKeep;
        endedKeep = null;
        CompressedKeep stale = ended == null ? null : ended.get();
        if (stale != null) removeKeep(stale.channel(), stale);
        Channel channel = linkChannel;
        CompressedKeep keep = linkKeep;
        linkChannel = null;
        linkKeep = null;
        linkRef = null;
        if (channel == null) return;
        linkStatus = "none";
        removeKeep(channel, keep);
    }

    private static void removeKeep(@Nullable Channel channel, CompressedKeep keep) {
        keep.markEnded();
        if (channel == null) return;
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
     * rate is forwarded submissions over those plus the marked buffer's writes passed to the encoder for changed
     * indices, changed content or a failed comparison, each counted when it is passed on; a write that fails at the
     * forwarder's own release of its input is in neither part. {@code not marked} counts every other buffer written
     * while enabled, and {@code decoder unlinked} the published writes passed on while the client decoder's neighbours
     * did not both link to it, outside the hit rate.
     */
    String stats() {
        long recompressed = indicesChanged + guardMismatch + forwardFailures;
        long eligible = forwarded + recompressed;
        String hit = eligible == 0 ? "n/a" : String.format(Locale.ROOT, "%.2f%%", 100.0 * forwarded / eligible);
        Channel backend = linkChannel;
        String link = backend != null && !backend.isActive() ? "backend-closed" : linkStatus;
        return "status=" + status + ", link=" + link + ", threshold=" + threshold + ", hit=" + hit
                + ", forwarded=" + forwarded + " (" + forwardedWireBytes
                + " B sent, " + forwardedInflatedBytes + " B inflated), recompressed: not marked=" + notMarked
                + ", indices changed=" + indicesChanged + ", guard mismatch=" + guardMismatch
                + ", comparison failed=" + forwardFailures + "; failures=" + failures
                + ", decoder unlinked=" + decoderUnlinked;
    }

    void resetStats() {
        forwarded = forwardedWireBytes = forwardedInflatedBytes = 0;
        notMarked = indicesChanged = guardMismatch = failures = forwardFailures = decoderUnlinked = 0;
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

    long decoderUnlinked() {
        return decoderUnlinked;
    }

    String status() {
        return status;
    }

    String linkStatus() {
        return linkStatus;
    }
}
