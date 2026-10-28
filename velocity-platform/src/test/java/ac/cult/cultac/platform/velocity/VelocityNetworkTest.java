package ac.cult.cultac.platform.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ac.cult.cultac.network.routing.NetworkPlatform.Holder;
import ac.cult.cultac.network.routing.NetworkPlatform.OnlinePlayer;
import ac.cult.reload.api.HostContext;
import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import io.netty.channel.Channel;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VelocityNetworkTest {
    private final ProxyServer proxy = mock(ProxyServer.class);
    private final EventManager events = mock(EventManager.class);
    private final Object plugin = new Object();
    private final VelocityNetwork network = new VelocityNetwork(proxy, plugin, player -> mock(Channel.class));

    private static Player player(UUID uuid, String name, String server) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getUsername()).thenReturn(name);
        if (server == null) {
            when(player.getCurrentServer()).thenReturn(Optional.empty());
        } else {
            ServerConnection connection = mock(ServerConnection.class);
            when(connection.getServerInfo())
                    .thenReturn(new ServerInfo(server, new InetSocketAddress("10.0.0.1", 25565)));
            when(player.getCurrentServer()).thenReturn(Optional.of(connection));
        }
        return player;
    }

    @Test
    void listsVelocitysPlayersWithTheirBackendServers() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        List<Player> players = List.of(player(alice, "Alice", "lobby"), player(bob, "Bob", null));
        when(proxy.getAllPlayers()).thenReturn(players);

        assertEquals(
                List.of(new OnlinePlayer(alice, "Alice", "lobby"), new OnlinePlayer(bob, "Bob", null)),
                List.copyOf(network.onlinePlayers()));
        assertTrue(network.proxy());
    }

    @Test
    void theAddressIsTheBoundOneOrThisMachinesNameForAWildcard() {
        when(proxy.getBoundAddress()).thenReturn(new InetSocketAddress("10.1.2.3", 25577));
        assertEquals("10.1.2.3:25577", network.address());

        when(proxy.getBoundAddress()).thenReturn(new InetSocketAddress(25577));
        String address = network.address();
        assertTrue(address.endsWith(":25577") && !address.startsWith("0.0.0.0"), address);
    }

    @Test
    void withoutModernForwardingOrRedisBungeeItHasNoSecretOrProxyId() {
        PluginManager plugins = mock(PluginManager.class);
        when(plugins.getPlugin("redisbungee")).thenReturn(Optional.empty());
        when(proxy.getPluginManager()).thenReturn(plugins);
        assertNull(network.defaultSecret());
        assertNull(network.redisBungeeId());
        assertNull(network.redisBungee());
    }

    @Test
    void theRuntimeHoldingAConnectionIsThisOneAnotherOrNone() {
        Channel channel = mock(Channel.class);
        assertEquals(Holder.THIS, VelocityNetwork.holder(null, channel));
        assertEquals(Holder.THIS, VelocityNetwork.holder(null, null));
        assertEquals(Holder.THIS, VelocityNetwork.holder(host("new", channel, "new"), channel));
        assertEquals(Holder.OTHER, VelocityNetwork.holder(host("new", channel, "old"), channel));
        assertEquals(Holder.NONE, VelocityNetwork.holder(host("new", channel, null), channel));
        assertEquals(Holder.NONE, VelocityNetwork.holder(host("new", null, null), null));
    }

    @Test
    void withoutAHostThisRuntimeHoldsEveryPlayer() {
        when(proxy.getPlayer(any(UUID.class))).thenReturn(Optional.empty());
        assertEquals(Holder.THIS, network.holder(UUID.randomUUID()));
    }

    private static HostContext host(String generation, Channel channel, String owner) {
        HostContext host = mock(HostContext.class);
        when(host.generationId()).thenReturn(generation);
        if (channel != null) when(host.ownerOf(channel)).thenReturn(owner);
        return host;
    }

    @Test
    @SuppressWarnings("unchecked")
    void listensForJoinsSwitchesAndQuitsUntilClosed() {
        when(proxy.getEventManager()).thenReturn(events);
        network.listen();
        network.listen();
        verify(events).register(eq(plugin), eq(PostLoginEvent.class), any(EventHandler.class));
        verify(events).register(eq(plugin), eq(ServerPostConnectEvent.class), any(EventHandler.class));
        verify(events).register(eq(plugin), eq(DisconnectEvent.class), any(EventHandler.class));

        network.close();
        network.close();
        verify(events, times(3)).unregister(eq(plugin), any(EventHandler.class));
    }
}
