package ac.cult.cultac.platform.velocity;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.jetbrains.annotations.Nullable;

/**
 * Sits on a backend channel directly before Velocity's compression decoder and, while the keep, decoder and mark are
 * adjacent, holds each compressed frame and arms the mark's one-shot token for the length of that frame's read.
 */
final class CompressedKeep extends ChannelInboundHandlerAdapter {
    static final String NAME = "cult-compressed-keep";

    private final CompressedForwarder forwarder;
    ByteBuf kept;
    int keptStart, keptEnd;
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
        boolean previousArmed = armed;
        boolean adjacent = ctx == keepContext && adjacent();
        // A zero data length byte marks a frame the backend left uncompressed.
        boolean hold = adjacent && frame.getByte(frame.readerIndex()) != 0;
        if (hold) frame.retain();
        kept = hold ? frame : null;
        keptStart = frame.readerIndex();
        keptEnd = frame.writerIndex();
        armed = hold;
        try {
            try {
                if (!adjacent) forwarder.unlinkDetached(this);
            } finally {
                ctx.fireChannelRead(msg);
            }
        } finally {
            kept = previous;
            keptStart = previousStart;
            keptEnd = previousEnd;
            armed = previousArmed;
            if (hold) frame.release();
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        kept = null;
        armed = false;
    }
}
