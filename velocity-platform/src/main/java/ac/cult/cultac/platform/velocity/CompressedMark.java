package ac.cult.cultac.platform.velocity;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import org.jetbrains.annotations.Nullable;

/**
 * Sits on a backend channel directly tail-side of Velocity's compression decoder. For the length of its own read it
 * publishes the inflated buffer, its indices, the kept compressed frame and, with the content check on, a copy of the
 * inflated bytes to the client's forwarder. It holds one reference to the inflated buffer while the mark is live, and
 * no other buffer can take its identity then. It marks only the first message to reach it inside a read the keep
 * armed, and only while the keep, the decoder and itself sit next to each other as {@link #attach} found them.
 */
final class CompressedMark extends ChannelInboundHandlerAdapter {
    static final String NAME = "cult-compressed-mark";

    private final CompressedKeep keep;
    private final CompressedForwarder forwarder;

    CompressedMark(CompressedKeep keep, CompressedForwarder forwarder) {
        this.keep = keep;
        this.forwarder = forwarder;
    }

    /** Caches the contexts of the keep, the compression decoder and this mark in {@code pipeline}. */
    void attach(ChannelPipeline pipeline) {
        keep.attach(
                pipeline.context(keep),
                pipeline.context(VelocityCompressedForward.COMPRESSION_DECODER),
                pipeline.context(this));
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
            forwarder.unlinkDetached(keep);
            ctx.fireChannelRead(msg);
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
