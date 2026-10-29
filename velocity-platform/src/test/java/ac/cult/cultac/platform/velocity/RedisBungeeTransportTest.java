package ac.cult.cultac.platform.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.imaginarycode.minecraft.redisbungee.AbstractRedisBungeeAPI;
import com.imaginarycode.minecraft.redisbungee.events.PubSubMessageEvent;
import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class RedisBungeeTransportTest {
    private static final List<String> CHANNELS = List.of("cultac.default.proxy-a", "cultac.default.all");

    private final AbstractRedisBungeeAPI api = new AbstractRedisBungeeAPI();
    private final ProxyServer proxy = mock(ProxyServer.class);
    private final EventManager events = mock(EventManager.class);
    private final PluginManager plugins = mock(PluginManager.class);
    private final Object plugin = new Object();

    @BeforeEach
    void setUp() {
        AbstractRedisBungeeAPI.instance = api;
        PluginContainer container = mock(PluginContainer.class);
        Mockito.doReturn(Optional.of(api)).when(container).getInstance();
        when(plugins.getPlugin("redisbungee")).thenReturn(Optional.of(container));
        when(proxy.getPluginManager()).thenReturn(plugins);
        when(proxy.getEventManager()).thenReturn(events);
    }

    @AfterEach
    void tearDown() {
        AbstractRedisBungeeAPI.instance = null;
    }

    @SuppressWarnings("unchecked")
    private EventHandler<Object> started(RedisBungeeTransport transport, List<String> received) {
        transport.start(CHANNELS, (channel, text) -> received.add(channel + " " + text));
        ArgumentCaptor<EventHandler<Object>> handler = ArgumentCaptor.forClass(EventHandler.class);
        verify(events).register(eq(plugin), eq((Class<Object>) (Class<?>) PubSubMessageEvent.class), handler.capture());
        return handler.getValue();
    }

    @Test
    void absentWithoutRedisBungee() {
        when(plugins.getPlugin("redisbungee")).thenReturn(Optional.empty());
        assertNull(RedisBungeeTransport.create(proxy, plugin));
        assertNull(RedisBungeeTransport.proxyId(proxy));
    }

    @Test
    void absentWhileRedisBungeeHasNotStarted() {
        AbstractRedisBungeeAPI.instance = null;
        assertNull(RedisBungeeTransport.proxyId(proxy));
    }

    @Test
    void namesThisProxyAsRedisBungeeDoes() {
        api.proxyId = "proxy-7";
        assertEquals("proxy-7", RedisBungeeTransport.proxyId(proxy));
    }

    @Test
    void registersTheChannelsAndDeliversOnlyTheirMessages() {
        RedisBungeeTransport transport = RedisBungeeTransport.create(proxy, plugin);
        assertNotNull(transport);
        List<String> received = new ArrayList<>();
        EventHandler<Object> handler = started(transport, received);

        assertEquals(CHANNELS, api.registered);
        handler.execute(new PubSubMessageEvent("cultac.default.all", "sig.{}"));
        handler.execute(new PubSubMessageEvent("cultac:profile", "{}"));
        assertEquals(List.of("cultac.default.all sig.{}"), received);
        transport.close();
    }

    @Test
    void publishesThroughRedisBungeeAndUnregistersOnlyItsHandler() {
        RedisBungeeTransport transport = RedisBungeeTransport.create(proxy, plugin);
        assertNotNull(transport);
        EventHandler<Object> handler = started(transport, new ArrayList<>());

        transport.publish("cultac.default.proxy-b", "sig.{\"v\":1}");
        transport.close();
        assertEquals(List.of("cultac.default.proxy-b"), api.publishedChannels);
        assertEquals(List.of("sig.{\"v\":1}"), api.published);
        verify(events).unregister(plugin, handler);
        assertEquals(CHANNELS, api.registered);

        transport.publish("cultac.default.proxy-b", "after close");
        assertEquals(1, api.published.size());
    }
}
