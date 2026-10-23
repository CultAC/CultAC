package ac.cult.cultac.platform.bukkit;

import ac.cult.cultac.network.CultNetworkManager;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.netty.ChannelInitializationBarrier;
import ac.cult.cultac.protocol.netty.CultDecoder;
import ac.cult.cultac.protocol.netty.CultEncoder;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import io.netty.channel.Channel;
import io.papermc.paper.network.ChannelInitializeListenerHolder;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.kyori.adventure.key.Key;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.ProtocolInfo;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftServer;

/** Adds and removes only Cult's two I/O handlers through Paper's initializer API. */
final class PaperInjector {
    private static final Key KEY = Key.key("cultac", "injector");
    private final CultNetworkManager manager;
    private volatile boolean registered;

    PaperInjector(CultNetworkManager manager) {
        this.manager = manager;
    }

    void enable() {
        registered = true;
    }

    void injectNew(Channel channel) {
        inject(channel, false);
    }

    java.util.concurrent.CompletionStage<Void> disconnect(Channel channel) {
        var connection = manager.connection(channel);
        if (connection == null) return CompletableFuture.completedFuture(null);
        var completion = new CompletableFuture<Void>();
        Runnable detach = () -> {
            try {
                connection
                        .removeHandlers(() -> {
                            var pipeline = channel.pipeline();
                            CultDecoder.uninstall(channel);
                            CultEncoder.uninstall(channel);
                        })
                        .thenCompose(ignored -> manager.disconnect(channel))
                        .whenComplete((ignored, failure) -> {
                            if (failure == null) completion.complete(null);
                            else completion.completeExceptionally(failure);
                        });
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
            }
        };
        try {
            if (channel.eventLoop().inEventLoop()) detach.run();
            else channel.eventLoop().execute(detach);
        } catch (Throwable failure) {
            completion.completeExceptionally(failure);
        }
        return completion;
    }

    void register() {
        registered = true;
        // Paper iterates an unordered listener map. Wait for every initializer,
        // including Via's, before attaching next to the native packet codecs.
        ChannelInitializeListenerHolder.addListener(
                KEY, channel -> ChannelInitializationBarrier.install(channel, () -> inject(channel, false)));
        var listener = ((CraftServer) Bukkit.getServer()).getServer().getConnection();
        if (listener != null)
            for (var connection : listener.getConnections()) {
                var channel = BukkitConnectionAdapter.channel(connection);
                if (channel != null) channel.eventLoop().execute(() -> inject(channel, true));
            }
    }

    private synchronized void inject(Channel channel, boolean existing) {
        if (!registered || manager.connection(channel) != null) return;
        if (!(channel.pipeline().get("packet_handler") instanceof Connection nativeConnection)) return;
        var inbound = existing ? installedPhase(channel, "decoder", PacketDecoder.class) : ConnectionPhase.HANDSHAKE;
        var outbound = existing ? installedPhase(channel, "encoder", PacketEncoder.class) : ConnectionPhase.HANDSHAKE;
        var connection = manager.createConnection(new BukkitConnectionAdapter(nativeConnection, channel), channel);
        if (existing) {
            connection.phase(PacketDirection.SERVERBOUND, inbound);
            connection.phase(PacketDirection.CLIENTBOUND, outbound);
            connection.resolveOwner();
        }
        CultDecoder.install(connection);
        CultEncoder.install(connection);
    }

    synchronized CompletionStage<Void> unregister() {
        registered = false;
        if (RuntimeEnvironment.host() == null) ChannelInitializeListenerHolder.removeListener(KEY);
        return CompletableFuture.allOf(manager.connections().stream()
                .map(connection -> {
                    var completion = new CompletableFuture<Void>();
                    connection
                            .channel()
                            .eventLoop()
                            .execute(() -> connection
                                    .removeHandlers(() -> {
                                        var pipeline = connection.channel().pipeline();
                                        CultDecoder.uninstall(connection.channel());
                                        CultEncoder.uninstall(connection.channel());
                                    })
                                    .whenComplete((ignored, failure) -> {
                                        if (failure == null) completion.complete(null);
                                        else completion.completeExceptionally(failure);
                                    }));
                    return completion;
                })
                .toArray(CompletableFuture[]::new));
    }
    /** Reload bootstrap reads native control metadata once, without changing either codec. */
    private static ConnectionPhase installedPhase(Channel channel, String name, Class<?> codec) {
        Object handler = channel.pipeline().get(name);
        if (!codec.isInstance(handler)) throw new IllegalStateException("Reload during unconfigured " + name);
        try {
            Field field = codec.getDeclaredField("protocolInfo");
            field.setAccessible(true);
            var protocol = (ProtocolInfo<?>) field.get(handler);
            return switch (protocol.id()) {
                case HANDSHAKING -> ConnectionPhase.HANDSHAKE;
                case STATUS -> ConnectionPhase.STATUS;
                case LOGIN -> ConnectionPhase.LOGIN;
                case CONFIGURATION -> ConnectionPhase.CONFIGURATION;
                case PLAY -> ConnectionPhase.PLAY;
            };
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot read installed protocol", failure);
        }
    }
}
