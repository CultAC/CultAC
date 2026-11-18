package ac.cult.cultac.platform.velocity;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.jetbrains.annotations.Nullable;

/**
 * Sits on a backend channel directly before Velocity's compression decoder and, while the keep, decoder and mark are
 * adjacent, copies each compressed frame into a private client frame, passes the decoder a slice of that copy, and
 * arms the mark's one-shot token for the length of that frame's read.
 */
final class CompressedKeep extends ChannelInboundHandlerAdapter {
    static final String NAME = "cult-compressed-keep";

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

    @Nullable
    ChannelHandlerContext markContext;

    CompressedKeep(CompressedForwarder forwarder) {
        this.forwarder = forwarder;
    }

    /** Caches the contexts of this keep, the compression decoder and the mark. */
    void attach(
            @Nullable ChannelHandlerContext keep,
            @Nullable ChannelHandlerContext decoder,
            @Nullable ChannelHandlerContext mark) {
        keepContext = keep;
        decoderContext = decoder;
        markContext = mark;
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
        // A zero data length byte marks a frame the backend left uncompressed.
        ByteBuf copy = adjacent && frame.getByte(frame.readerIndex()) != 0 ? forwarder.frame(ctx.alloc(), frame) : null;
        ByteBuf slice = null;
        int start = 0;
        if (copy != null) {
            int length = frame.readableBytes();
            start = copy.writerIndex() - length;
            try {
                slice = copy.retainedSlice(start, length);
                frame.release();
            } catch (RuntimeException | Error failure) {
                CompressedForwarder.release(copy, CompressedForwarder.release(slice, null));
                throw failure;
            }
        }
        kept = copy;
        keptStart = start;
        keptEnd = copy == null ? 0 : copy.writerIndex();
        keptSlice = slice;
        armed = copy != null;
        try {
            try {
                if (!adjacent) forwarder.unlinkDetached(this);
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
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        kept = null;
        keptSlice = null;
        armed = false;
    }
}
