package ac.cult.cultac.platform.velocity;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * Sits on a backend channel directly head-side of Velocity's compression decoder and holds each compressed frame for
 * the length of its own read. It owns one reference to that frame only inside {@link #channelRead}.
 */
final class CompressedKeep extends ChannelInboundHandlerAdapter {
    static final String NAME = "cult-compressed-keep";

    private final CompressedForwarder forwarder;
    ByteBuf kept;
    int keptStart, keptEnd;

    CompressedKeep(CompressedForwarder forwarder) {
        this.forwarder = forwarder;
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
        // A zero data length byte marks a frame the backend left uncompressed.
        boolean compressed = frame.getByte(frame.readerIndex()) != 0;
        if (compressed) frame.retain();
        kept = compressed ? frame : null;
        keptStart = frame.readerIndex();
        keptEnd = frame.writerIndex();
        try {
            ctx.fireChannelRead(msg);
        } finally {
            kept = previous;
            keptStart = previousStart;
            keptEnd = previousEnd;
            if (compressed) frame.release();
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        kept = null;
    }
}
