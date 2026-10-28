package ac.cult.cultac.platform.velocity;

import ac.cult.cultac.network.routing.MessageTransport;
import ac.cult.cultac.network.routing.NetworkModule;
import ac.cult.cultac.network.routing.NetworkPlatform;
import ac.cult.cultac.platform.velocity.bridge.BridgeService;
import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.net.InetSocketAddress;
import java.util.Collection;
import org.jetbrains.annotations.Nullable;

/**
 * The proxy's side of the network service: Velocity's own player list, whichever runtime holds each connection, and
 * its join, quit and backend-switch events.
 */
final class VelocityNetwork implements NetworkPlatform {
    private final ProxyServer proxy;
    private final Object plugin;
    private final EventHandler<PostLoginEvent> joined = event -> changed(event.getPlayer());
    private final EventHandler<ServerPostConnectEvent> switched = event -> changed(event.getPlayer());
    private final EventHandler<DisconnectEvent> left = this::left;
    private boolean listening;

    VelocityNetwork(ProxyServer proxy, Object plugin) {
        this.proxy = proxy;
        this.plugin = plugin;
    }

    synchronized void listen() {
        if (listening) return;
        listening = true;
        proxy.getEventManager().register(plugin, PostLoginEvent.class, joined);
        proxy.getEventManager().register(plugin, ServerPostConnectEvent.class, switched);
        proxy.getEventManager().register(plugin, DisconnectEvent.class, left);
    }

    synchronized void close() {
        if (!listening) return;
        listening = false;
        proxy.getEventManager().unregister(plugin, joined);
        proxy.getEventManager().unregister(plugin, switched);
        proxy.getEventManager().unregister(plugin, left);
    }

    private static void changed(Player player) {
        NetworkModule.playerChanged(player.getUniqueId(), player.getUsername(), server(player));
    }

    /** Leaves the player listed while Velocity still holds another connection for them. */
    private void left(DisconnectEvent event) {
        var uuid = event.getPlayer().getUniqueId();
        if (proxy.getPlayer(uuid).isEmpty()) NetworkModule.playerLeft(uuid);
    }

    private static @Nullable String server(Player player) {
        return player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse(null);
    }

    @Override
    public Collection<OnlinePlayer> onlinePlayers() {
        return proxy.getAllPlayers().stream()
                .map(player -> new OnlinePlayer(player.getUniqueId(), player.getUsername(), server(player)))
                .toList();
    }

    @Override
    public String address() {
        InetSocketAddress bound = proxy.getBoundAddress();
        boolean wildcard = bound.getAddress() == null || bound.getAddress().isAnyLocalAddress();
        return NetworkPlatform.address(wildcard ? null : bound.getHostString(), bound.getPort());
    }

    @Override
    public boolean proxy() {
        return true;
    }

    @Override
    public @Nullable String redisBungeeId() {
        return RedisBungeeTransport.proxyId(proxy);
    }

    @Override
    public byte @Nullable [] defaultSecret() {
        byte[] secret = BridgeService.forwardingSecret(proxy);
        return secret.length == 0 ? null : secret;
    }

    @Override
    public @Nullable MessageTransport redisBungee() {
        return RedisBungeeTransport.create(proxy, plugin);
    }
}
