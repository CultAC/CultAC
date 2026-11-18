package ac.cult.cultac.platform.velocity;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.jetbrains.annotations.Nullable;

/**
 * Sits on a backend channel directly after Velocity's compression decoder and, for a read the keep armed, publishes
 * the inflated buffer, its indices, a copy of its bytes and a private client frame to the forwarder for that read.
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
        boolean armed = keep.armed;
        keep.armed = false;
        if (forwarder.disabled || !(msg instanceof ByteBuf inflated)) {
            ctx.fireChannelRead(msg);
            return;
        }
        if (ctx != keep.markContext || !keep.adjacent()) {
            try {
                forwarder.unlinkDetached(keep);
            } finally {
                ctx.fireChannelRead(msg);
            }
            return;
        }
        ByteBuf kept = keep.kept;
        if (!armed || kept == null || inflated == kept) {
            ctx.fireChannelRead(msg);
            return;
        }
        CompressedForwarder target = forwarder;
        ByteBuf previous = target.markBuf;
        int previousReader = target.markReader();
        int previousWriter = target.markWriter();
        ByteBuf previousCopy = target.markCopy();
        ByteBuf previousFrame = target.markFrame();
        ByteBuf copy = null;
        ByteBuf frame = null;
        int n = inflated.readableBytes();
        if (n >= target.threshold()) {
            frame = target.frame(ctx.alloc(), kept, keep.keptStart, keep.keptEnd, n);
            copy = frame == null ? null : copy(ctx, inflated, n);
            if (copy == null) {
                if (frame != null) frame.release();
                ctx.fireChannelRead(msg);
                return;
            }
        }
        inflated.retain();
        target.mark(inflated, inflated.readerIndex(), inflated.writerIndex(), copy, frame);
        try {
            ctx.fireChannelRead(msg);
        } finally {
            boolean untaken = frame != null && target.markFrame() == frame;
            target.mark(previous, previousReader, previousWriter, previousCopy, previousFrame);
            inflated.release();
            if (copy != null) copy.release();
            if (untaken) frame.release();
        }
    }

    /** An exception that reaches the mark, such as the decoder's, spends the token of the read it is in. */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        keep.armed = false;
        ctx.fireExceptionCaught(cause);
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
