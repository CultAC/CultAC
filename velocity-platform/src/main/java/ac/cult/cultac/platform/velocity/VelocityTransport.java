package ac.cult.cultac.platform.velocity;

import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.CultNetworkManager;
import ac.cult.cultac.network.PacketOwner;
import ac.cult.cultac.network.PlatformConnection;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.netty.CultDecoder;
import ac.cult.cultac.protocol.netty.CultEncoder;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.netty.channel.Channel;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import net.kyori.adventure.text.Component;

/** Intercepts client-facing frames, after Velocity's entity-ID rewriting and before compression. */
final class VelocityTransport {
    @FunctionalInterface
    interface AdapterFactory {
        PlatformConnection create(Player player, Channel channel, PacketOwner bedrock);
    }

    private final ProxyServer proxy;
    private final Object plugin;
    private final CultNetworkManager manager;
    private final AdapterFactory platform;
    private final Function<Channel, PacketOwner> bedrockOwners;
    private final Method connectionMethod;
    private final Method channelMethod;
    private final VelocityClientSupport clients;
    private volatile boolean running;

    VelocityTransport(
            ProxyServer proxy,
            Object plugin,
            CultNetworkManager manager,
            AdapterFactory platform,
            Function<Channel, PacketOwner> bedrockOwners) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.manager = manager;
        this.platform = platform;
        this.bedrockOwners = bedrockOwners;
        this.clients = new VelocityClientSupport(proxy);
        try {
            // Velocity has no public packet API. Resolve this narrow, checked boundary once.
            ClassLoader loader = proxy.getClass().getClassLoader();
            connectionMethod = Class.forName(
                            "com.velocitypowered.proxy.connection.client.ConnectedPlayer", false, loader)
                    .getMethod("getConnection");
            channelMethod = connectionMethod.getReturnType().getMethod("getChannel");
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                    "Unsupported Velocity transport; expected ConnectedPlayer.getConnection().getChannel()", failure);
        }
    }

    synchronized void install() {
        if (!proxy.getAllPlayers().isEmpty()) {
            throw new IllegalStateException("CultAC must start before players connect");
        }
        running = true;
        proxy.getEventManager().register(plugin, this);
    }

    @Subscribe
    public EventTask login(PostLoginEvent event) {
        // Velocity awaits this event in CONFIG before connecting the first backend.
        Player player = event.getPlayer();
        if (!running) {
            return null;
        }
        final Channel channel;
        final PacketOwner bedrock;
        try {
            channel = channel(player);
            // Geyser sends login acknowledgement before its translator registers the Java UUID.
            // The downstream channel already exists, so its bridge tap is the connection's identity.
            bedrock = bedrockOwners.apply(channel);
            boolean translated = bedrock != null || clients.isBedrock(player.getUniqueId());
            // A Bedrock session needs the Geyser bridge's tap; never inspect it as a Java client.
            if (translated && bedrock == null) {
                player.disconnect(Component.text(
                        "CultAC on this proxy inspects Bedrock players only through Geyser on this proxy."));
                return null;
            }
        } catch (RuntimeException failure) {
            return EventTask.resumeWhenComplete(CompletableFuture.failedFuture(failure));
        }
        CompletableFuture<Void> attached;
        try {
            attached = CompletableFuture.runAsync(
                    () -> {
                        if (running && channel.isActive()) {
                            attach(player, channel, bedrock);
                        }
                    },
                    channel.eventLoop());
        } catch (RuntimeException failure) {
            attached = CompletableFuture.failedFuture(failure);
        }
        attached.whenComplete((ignored, failure) -> {
            if (failure != null) {
                player.disconnect(Component.text("Unable to initialize the anticheat connection."));
            }
        });
        return EventTask.resumeWhenComplete(attached);
    }

    Channel channel(Player player) {
        try {
            return (Channel) channelMethod.invoke(connectionMethod.invoke(player));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot access Velocity client channel", failure);
        }
    }

    private synchronized void attach(Player player, Channel channel, PacketOwner bedrock) {
        if (!running) {
            return;
        }
        var pipeline = channel.pipeline();
        if (pipeline.get("minecraft-decoder") == null || pipeline.get("minecraft-encoder") == null) {
            throw new IllegalStateException("Unsupported Velocity client pipeline: " + pipeline.names());
        }
        if (manager.connection(channel) != null) {
            throw new IllegalStateException("Velocity connection already attached");
        }
        var protocols = VelocityProtocols.read(player, channel);
        var adapter = (VelocityConnectionAdapter) platform.create(player, channel, bedrock);
        adapter.bindProtocols(protocols);
        var connection = manager.createConnection(adapter, channel);
        adapter.attach(connection);
        connection.phase(PacketDirection.SERVERBOUND, ConnectionPhase.CONFIGURATION);
        connection.phase(PacketDirection.CLIENTBOUND, ConnectionPhase.CONFIGURATION);
        connection.resolveOwner();
        // Authentication enables compression (including Via's relocation) before
        // PostLogin CONFIG. Keep these handlers stable for the whole session.
        VelocityPacketPipeline.install(connection);
        connection.execute(connection::prepare);
    }

    synchronized CompletionStage<Void> remove() {
        running = false;
        proxy.getEventManager().unregisterListener(plugin, this);
        return CompletableFuture.allOf(
                manager.connections().stream().map(this::detach).toArray(CompletableFuture[]::new));
    }

    private CompletableFuture<Void> detach(CultConnection connection) {
        try {
            // Enter I/O before requesting removal; removal waits for accepted packet work.
            return CompletableFuture.supplyAsync(
                            () -> connection.removeHandlers(() -> {
                                var pipeline = connection.channel().pipeline();
                                if (pipeline.get(CultDecoder.NAME) != null) {
                                    pipeline.remove(CultDecoder.NAME);
                                }
                                if (pipeline.get(CultEncoder.NAME) != null) {
                                    pipeline.remove(CultEncoder.NAME);
                                }
                            }),
                            connection.channel().eventLoop())
                    .thenCompose(CompletionStage::toCompletableFuture);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }
}
