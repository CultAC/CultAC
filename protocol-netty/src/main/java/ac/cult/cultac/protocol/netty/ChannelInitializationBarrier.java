package ac.cult.cultac.protocol.netty;

import ac.cult.cultac.network.CultNetworkManager;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.Objects;

/** Finishes attachment after synchronous initializers, before activation can start reads. */
public final class ChannelInitializationBarrier extends ChannelInboundHandlerAdapter {
    private final Runnable initialize;

    private ChannelInitializationBarrier(Runnable initialize) {
        this.initialize = Objects.requireNonNull(initialize);
    }

    public static void install(Channel channel, Runnable initialize) {
        channel.pipeline().addFirst("cult-initializer", new ChannelInitializationBarrier(initialize));
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) {
        try {
            initialize.run();
        } catch (Throwable failure) {
            CultNetworkManager.reportFailure("channel initialization", failure);
        } finally {
            try {
                if (ctx.pipeline().context(this) != null) ctx.pipeline().remove(this);
            } catch (Throwable failure) {
                CultNetworkManager.reportFailure("channel initializer removal", failure);
            }
        }
        ctx.fireChannelRegistered();
    }
}
