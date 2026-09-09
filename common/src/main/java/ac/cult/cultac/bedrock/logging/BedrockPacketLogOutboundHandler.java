package ac.cult.cultac.bedrock.logging;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.util.function.Consumer;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;

/** Placed before the bridge in pipeline order, hence after it in outbound traversal. */
public final class BedrockPacketLogOutboundHandler extends ChannelOutboundHandlerAdapter {
    private final Consumer<BedrockPacket> observer;

    public BedrockPacketLogOutboundHandler(Consumer<BedrockPacket> observer) {
        this.observer = observer;
    }

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
        if (message instanceof BedrockPacketWrapper wrapper) {
            observer.accept(wrapper.getPacket());
        }
        context.write(message, promise);
    }
}
