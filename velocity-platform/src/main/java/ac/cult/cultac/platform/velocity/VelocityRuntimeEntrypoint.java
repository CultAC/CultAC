package ac.cult.cultac.platform.velocity;

import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.runtime.EngineRuntime;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.reload.api.*;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.netty.channel.Channel;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;

public final class VelocityRuntimeEntrypoint implements RuntimeEntrypoint {
    @Override
    public RuntimeInstance create(HostContext context) throws Exception {
        RuntimeEnvironment.initialize(context);
        var platform = new VelocityPlatform(
                (ProxyServer) context.server(), context.plugin(), context.directory(), (Logger) context.logger());
        return new EngineRuntime() {
            public void load() {
                platform.load();
            }

            public void start() {
                platform.start();
            }

            public void attach(Object channel, Object player) {
                platform.attach((Player) player, (Channel) channel);
            }

            public Map<String, Object> release(Object channel) {
                return platform.release((Channel) channel);
            }

            public void adopt(Object channel, Object player, Map<String, Object> state) {
                platform.adopt((Player) player, (Channel) channel, state);
            }

            protected CompletionStage<Void> disconnectChannel(Channel connection) {
                return platform.disconnect(connection);
            }

            public CompletionStage<Void> close() {
                platform.close();
                return CompletableFuture.completedFuture(null);
            }

            protected Sender sender(Object source) {
                return platform.senders().wrap((CommandSource) source);
            }
        };
    }
}
