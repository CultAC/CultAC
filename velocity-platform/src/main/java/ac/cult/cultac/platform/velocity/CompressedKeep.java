package ac.cult.cultac.platform.velocity;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.jetbrains.annotations.Nullable;

/**
 * Sits on a backend channel directly head-side of Velocity's compression decoder and holds each compressed frame for
 * the length of its own read. It owns one reference to that frame only inside {@link #channelRead}. It holds a frame
 * and arms its one-shot token for the mark only while the keep, the decoder and the mark sit next to each other as
 * {@link CompressedMark#attach} found them; otherwise the frame passes unheld and the link is removed. The forwarder
 * sends a held frame's bytes as they are at write time: no handler may write into a frame the keep has passed on.
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
        if (!adjacent) forwarder.unlinkDetached(this);
        // A zero data length byte marks a frame the backend left uncompressed.
        boolean hold = adjacent && frame.getByte(frame.readerIndex()) != 0;
        if (hold) frame.retain();
        kept = hold ? frame : null;
        keptStart = frame.readerIndex();
        keptEnd = frame.writerIndex();
        armed = hold;
        try {
            ctx.fireChannelRead(msg);
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
