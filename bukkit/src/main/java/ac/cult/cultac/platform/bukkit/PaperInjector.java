package ac.cult.cultac.platform.bukkit;

import ac.cult.cultac.network.CultNetworkManager;
import ac.cult.cultac.network.DormancyController;
import ac.cult.cultac.protocol.netty.CultDecoder;
import ac.cult.cultac.protocol.netty.CultEncoder;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import io.netty.channel.Channel;
import io.papermc.paper.network.ChannelInitializeListenerHolder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.kyori.adventure.key.Key;
import net.minecraft.network.Connection;

/** Adds and removes only Cult's two I/O handlers through Paper's initializer API. */
final class PaperInjector {
    private static final Key KEY = Key.key("cultac", "injector");
    private final CultNetworkManager manager;
    private final PaperDormancy dormancy;
    private volatile boolean registered;

    PaperInjector(CultNetworkManager manager, PaperDormancy dormancy) {
        this.manager = manager;
        this.dormancy = dormancy;
    }

    void enable() {
        registered = true;
    }

    void injectNew(Channel channel) {
        inject(channel);
    }

    CompletionStage<Void> disconnect(Channel channel) {
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

    private synchronized void inject(Channel channel) {
        if (!registered || manager.connection(channel) != null) return;
        if (!(channel.pipeline().get("packet_handler") instanceof Connection nativeConnection)) return;
        boolean dormant = dormancy.dormant();
        if (dormant && PaperDormancy.skips(channel)) {
            RuntimeEnvironment.release(channel, CompletableFuture.completedFuture(null));
            return;
        }
        var connection = manager.createConnection(new BukkitConnectionAdapter(nativeConnection, channel), channel);
        if (dormant) DormancyController.joinedDormant(connection);
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
}
