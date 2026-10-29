package ac.cult.cultac.platform.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ac.cult.cultac.network.routing.Backlog;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class RedisBungeeTransportTest {
    private static final List<String> CHANNELS = List.of("cultac.0123abcd.proxy-a", "cultac.0123abcd.all");

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
        handler.execute(new PubSubMessageEvent("cultac.0123abcd.all", "sig.{}"));
        handler.execute(new PubSubMessageEvent("cultac:profile", "{}"));
        assertEquals(List.of("cultac.0123abcd.all sig.{}"), received);
        transport.close();
    }

    @Test
    void publishesThroughRedisBungeeAndUnregistersOnlyItsHandler() {
        RedisBungeeTransport transport = RedisBungeeTransport.create(proxy, plugin);
        assertNotNull(transport);
        EventHandler<Object> handler = started(transport, new ArrayList<>());

        transport.publish("cultac.0123abcd.proxy-b", "sig.{\"v\":1}");
        transport.close();
        assertEquals(List.of("cultac.0123abcd.proxy-b"), api.publishedChannels);
        assertEquals(List.of("sig.{\"v\":1}"), api.published);
        verify(events).unregister(plugin, handler);
        assertEquals(CHANNELS, api.registered);

        transport.publish("cultac.0123abcd.proxy-b", "after close");
        assertEquals(1, api.published.size());
    }

    @Test
    void whileRedisBungeeBlocksAtMostTheBacklogWaitsAndItsPlacesComeBackOnceItAnswers() throws Exception {
        RedisBungeeTransport transport = RedisBungeeTransport.create(proxy, plugin);
        assertNotNull(transport);
        CountDownLatch gate = new CountDownLatch(1);
        api.gate = gate;
        try {
            for (int i = 0; i < Backlog.MAX_ITEMS; i++) transport.publish("cultac.0123abcd.all", "m" + i);
            transport.publish("cultac.0123abcd.all", "dropped");
            gate.countDown();
            await(() -> api.publishedCount() == Backlog.MAX_ITEMS);

            transport.publish("cultac.0123abcd.all", "after");
            await(() -> api.publishedCount() == Backlog.MAX_ITEMS + 1);
            assertEquals("after", api.published.getLast());
            assertTrue(!api.published.contains("dropped"));
        } finally {
            gate.countDown();
            transport.close();
        }
    }

    @Test
    void theBacklogBoundsTheCharactersWaitingToo() throws Exception {
        RedisBungeeTransport transport = RedisBungeeTransport.create(proxy, plugin);
        assertNotNull(transport);
        CountDownLatch gate = new CountDownLatch(1);
        api.gate = gate;
        String half = "x".repeat((int) (Backlog.MAX_BYTES / 2) + 1);
        try {
            transport.publish("cultac.0123abcd.all", half);
            transport.publish("cultac.0123abcd.all", half);
            transport.publish("cultac.0123abcd.all", "small");
            gate.countDown();
            await(() -> api.publishedCount() == 2);
            assertEquals("small", api.published.getLast());
        } finally {
            gate.countDown();
            transport.close();
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out");
            Thread.sleep(20);
        }
    }
}
