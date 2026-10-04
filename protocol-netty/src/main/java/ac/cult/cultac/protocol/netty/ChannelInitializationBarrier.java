package ac.cult.cultac.protocol.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/** Finishes attachment after synchronous initializers, before activation can start reads. */
public final class ChannelInitializationBarrier extends ChannelInboundHandlerAdapter {
    private final Runnable initialize;

    private ChannelInitializationBarrier(Runnable initialize) {
        this.initialize = java.util.Objects.requireNonNull(initialize);
    }

    public static void install(Channel channel, Runnable initialize) {
        channel.pipeline().addFirst("cult-initializer", new ChannelInitializationBarrier(initialize));
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) {
        try {
            initialize.run();
        } catch (Throwable failure) {
            ctx.fireExceptionCaught(failure);
            ctx.close();
            return;
        } finally {
            ctx.pipeline().remove(this);
        }
        ctx.fireChannelRegistered();
    }
}
