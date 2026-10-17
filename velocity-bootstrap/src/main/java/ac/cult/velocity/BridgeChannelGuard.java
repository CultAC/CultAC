package ac.cult.velocity;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Drops every plugin message a client sends on the CultAC bridge channel, after Velocity decodes it and before
 * Velocity forwards it to a backend.
 *
 * <p>Velocity forwards plugin messages on channels missing from its {@code ChannelRegistrar} without firing
 * {@code PluginMessageEvent}, and the bridge channel stays unregistered so backend frames keep their order. This
 * handler sits on the client channel only, so it never sees backend-to-client traffic.
 */
@ChannelHandler.Sharable
final class BridgeChannelGuard extends ChannelInboundHandlerAdapter {
    private static final String NAME = "cultac-bridge-guard";
    private static final String CHANNEL = "cultac:bridge";
    private static final String DECODER = "minecraft-decoder";

    private final Class<?> pluginMessage;
    private final MethodHandle channel;

    private BridgeChannelGuard(Class<?> pluginMessage) throws ReflectiveOperationException {
        this.pluginMessage = pluginMessage;
        this.channel = MethodHandles.publicLookup()
                .findVirtual(pluginMessage, "getChannel", MethodType.methodType(String.class))
                .asType(MethodType.methodType(String.class, Object.class));
    }

    static BridgeChannelGuard forProxy(ClassLoader proxyLoader) throws ReflectiveOperationException {
        return new BridgeChannelGuard(
                Class.forName("com.velocitypowered.proxy.protocol.packet.PluginMessagePacket", false, proxyLoader));
    }

    void install(Channel client) {
        var pipeline = client.pipeline();
        if (pipeline.get(NAME) == null) pipeline.addAfter(DECODER, NAME, this);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (pluginMessage.isInstance(message) && isBridge(message)) {
            ReferenceCountUtil.release(message);
            return;
        }
        ctx.fireChannelRead(message);
    }

    private boolean isBridge(Object message) {
        try {
            return CHANNEL.equals((String) channel.invokeExact(message));
        } catch (Throwable unreadable) {
            return true;
        }
    }
}
