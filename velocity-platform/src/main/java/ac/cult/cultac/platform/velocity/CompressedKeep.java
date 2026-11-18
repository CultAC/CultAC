package ac.cult.cultac.platform.velocity;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.DecoderException;
import java.lang.invoke.MethodHandle;
import org.jetbrains.annotations.Nullable;

/**
 * Sits on a backend channel directly before Velocity's compression decoder and, while the keep, decoder and mark are
 * adjacent and the decoder has no packet limiter, copies each forwardable compressed frame into a private client
 * frame, passes the decoder a slice of that copy, and arms the mark's one-shot token for that frame's read.
 */
final class CompressedKeep extends ChannelInboundHandlerAdapter {
    static final String NAME = "cult-compressed-keep";
    // A packet limiter runs inside the decoder between its inflate and its publish.
    static final String LIMITED = "backend-limiter";

    private final CompressedForwarder forwarder;
    // The private client frame: varint(length), then the backend's frame at [keptStart, keptEnd).
    ByteBuf kept;
    int keptStart, keptEnd;
    // The slice of kept's backend frame the decoder receives.
    @Nullable
    ByteBuf keptSlice;
    // Spent by the first message to reach the mark inside the read that armed it.
    boolean armed;
    private @Nullable ChannelHandlerContext keepContext, decoderContext;
    private @Nullable MethodHandle limiter;

    @Nullable
    ChannelHandlerContext markContext;

    CompressedKeep(CompressedForwarder forwarder) {
        this.forwarder = forwarder;
    }

    /** Caches the contexts of this keep, the compression decoder and the mark, and the decoder's limiter getter. */
    void attach(
            @Nullable ChannelHandlerContext keep,
            @Nullable ChannelHandlerContext decoder,
            @Nullable ChannelHandlerContext mark,
            @Nullable MethodHandle limiter) {
        keepContext = keep;
        decoderContext = decoder;
        markContext = mark;
        this.limiter = limiter;
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

    /** Whether {@code frame} is compressed with a data length the forwarder could send. */
    private boolean held(ByteBuf frame) {
        int n = CompressedForwarder.dataLength(frame, frame.readerIndex(), frame.writerIndex());
        return n > 0 && n >= forwarder.threshold() && n <= CompressedForwarder.MAX_FORWARDED_LENGTH;
    }

    /** Whether the cached mark is in the pipeline directly after the decoder, which directly follows this keep. */
    boolean adjacent() {
        ChannelHandlerContext keep = keepContext;
        ChannelHandlerContext decoder = decoderContext;
        ChannelHandlerContext mark = markContext;
        return keep != null
                && decoder != null
                && mark != null
                && !mark.isRemoved()
                && CompressedForwarder.follows(mark, decoder)
                && CompressedForwarder.follows(decoder, keep);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (forwarder.disabled || !(msg instanceof ByteBuf frame) || !frame.isReadable()) {
            ctx.fireChannelRead(msg);
            return;
        }
        ByteBuf previous = kept;
        int previousStart = keptStart;
        int previousEnd = keptEnd;
        ByteBuf previousSlice = keptSlice;
        boolean previousArmed = armed;
        boolean adjacent = ctx == keepContext && adjacent();
        boolean limited = adjacent && limited(limiter);
        ByteBuf copy = adjacent && !limited && held(frame) ? forwarder.frame(ctx.alloc(), frame) : null;
        ByteBuf slice = null;
        Throwable released = null;
        int start = 0;
        if (copy != null) {
            int length = frame.readableBytes();
            start = copy.writerIndex() - length;
            try {
                slice = copy.retainedSlice(start, length);
            } catch (RuntimeException | Error failure) {
                copy.release();
                copy = null;
            }
            if (slice != null) released = CompressedForwarder.release(frame, null);
        }
        kept = copy;
        keptStart = start;
        keptEnd = copy == null ? 0 : copy.writerIndex();
        keptSlice = slice;
        armed = copy != null;
        try {
            try {
                if (!adjacent) forwarder.unlinkDetached(this);
                else if (limited) forwarder.unlinkDetached(this, LIMITED);
            } finally {
                ctx.fireChannelRead(slice == null ? msg : slice);
            }
        } finally {
            kept = previous;
            keptStart = previousStart;
            keptEnd = previousEnd;
            keptSlice = previousSlice;
            armed = previousArmed;
            if (copy != null) copy.release();
        }
        // A failed release of the input surfaces after the fire, wrapped as MessageToMessageDecoder wraps it.
        CompressedForwarder.rethrow(
                released instanceof RuntimeException failure && !(failure instanceof DecoderException)
                        ? new DecoderException(failure)
                        : released);
    }

    /** Whether the decoder's packet limiter is set or cannot be read, by one read of the cached getter. */
    boolean limited() {
        return limited(limiter);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        kept = null;
        keptSlice = null;
        armed = false;
    }
}
