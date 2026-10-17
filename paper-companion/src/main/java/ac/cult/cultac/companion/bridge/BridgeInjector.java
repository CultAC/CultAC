package ac.cult.cultac.companion.bridge;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.papermc.paper.network.ChannelInitializeListenerHolder;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import net.minecraft.network.Connection;
import net.minecraft.network.HandlerNames;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConnectionListener;

/** Places the bridge handler after each connection's packet decoder, on new and existing channels. */
final class BridgeInjector {
    private static final Key KEY = Key.key("cultac", "companion_bridge");

    private final BridgeService service;

    BridgeInjector(BridgeService service) {
        this.service = service;
    }

    void install() {
        ChannelInitializeListenerHolder.addListener(KEY, this::inject);
        forEachChannel(channel -> channel.eventLoop().execute(() -> inject(channel)));
    }

    void uninstall() {
        ChannelInitializeListenerHolder.removeListener(KEY);
        forEachChannel(channel -> channel.eventLoop().execute(() -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(BridgeChannelHandler.NAME) != null) pipeline.remove(BridgeChannelHandler.NAME);
        }));
    }

    private void inject(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(BridgeChannelHandler.NAME) != null || pipeline.get(HandlerNames.DECODER) == null) return;
        if (!(pipeline.get(HandlerNames.PACKET_HANDLER) instanceof Connection connection)) return;
        pipeline.addAfter(
                HandlerNames.DECODER, BridgeChannelHandler.NAME, new BridgeChannelHandler(service, connection));
    }

    private static void forEachChannel(Consumer<Channel> action) {
        ServerConnectionListener listener = MinecraftServer.getServer().getConnection();
        if (listener == null) return;
        List<Connection> connections = listener.getConnections();
        synchronized (connections) {
            for (Connection connection : connections) {
                Channel channel = connection.channel;
                if (channel != null && channel.isOpen()) action.accept(channel);
            }
        }
    }
}
