package ac.cult.cultac.platform.velocity;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * Sits on a backend channel directly tail-side of Velocity's compression decoder. For the length of its own read it
 * publishes the inflated buffer, its indices and the kept compressed frame to the client's forwarder, holding one
 * reference to the inflated buffer so that no other buffer can take its identity while the mark is live.
 */
final class CompressedMark extends ChannelInboundHandlerAdapter {
    static final String NAME = "cult-compressed-mark";

    private final CompressedKeep keep;
    private final CompressedForwarder forwarder;

    CompressedMark(CompressedKeep keep, CompressedForwarder forwarder) {
        this.keep = keep;
        this.forwarder = forwarder;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf kept = keep.kept;
        if (kept == null || forwarder.disabled || !(msg instanceof ByteBuf inflated) || inflated == kept) {
            ctx.fireChannelRead(msg);
            return;
        }
        CompressedForwarder target = forwarder;
        ByteBuf previous = target.markBuf;
        int previousReader = target.markReader();
        int previousWriter = target.markWriter();
        ByteBuf previousKept = target.keptBuf();
        int previousStart = target.keptStart();
        int previousEnd = target.keptEnd();
        inflated.retain();
        target.mark(inflated, inflated.readerIndex(), inflated.writerIndex(), kept, keep.keptStart, keep.keptEnd);
        try {
            ctx.fireChannelRead(msg);
        } finally {
            target.mark(previous, previousReader, previousWriter, previousKept, previousStart, previousEnd);
            inflated.release();
        }
    }
}
