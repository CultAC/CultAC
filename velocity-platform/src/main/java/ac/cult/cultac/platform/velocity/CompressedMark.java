package ac.cult.cultac.platform.velocity;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.jetbrains.annotations.Nullable;

/**
 * Sits on a backend channel directly tail-side of Velocity's compression decoder. For the length of its own read it
 * publishes the inflated buffer, its indices, the kept compressed frame and, with the content check on, a copy of the
 * inflated bytes to the client's forwarder, holding one reference to the inflated buffer so that no other buffer can
 * take its identity while the mark is live.
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
        ByteBuf previousCopy = target.markCopy();
        ByteBuf copy = null;
        int n = inflated.readableBytes();
        if (target.guard() && n >= target.threshold()) {
            copy = copy(ctx, inflated, n);
            if (copy == null) {
                ctx.fireChannelRead(msg);
                return;
            }
        }
        inflated.retain();
        target.mark(inflated, inflated.readerIndex(), inflated.writerIndex(), kept, keep.keptStart, keep.keptEnd, copy);
        try {
            ctx.fireChannelRead(msg);
        } finally {
            target.mark(
                    previous, previousReader, previousWriter, previousKept, previousStart, previousEnd, previousCopy);
            inflated.release();
            if (copy != null) copy.release();
        }
    }

    /** The {@code n} readable bytes of {@code inflated} in a new buffer, or null when it cannot be allocated. */
    private static @Nullable ByteBuf copy(ChannelHandlerContext ctx, ByteBuf inflated, int n) {
        ByteBuf copy = null;
        try {
            copy = ctx.alloc().buffer(n, n);
            copy.writeBytes(inflated, inflated.readerIndex(), n);
            return copy;
        } catch (RuntimeException | OutOfMemoryError failure) {
            if (copy != null) copy.release();
            return null;
        }
    }
}
