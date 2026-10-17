package ac.cult.cultac.platform.velocity;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.platform.api.PlatformLoader;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class VelocityTransportLoginTest {
    @Test
    void failedAttachDisconnectsThePlayer() throws Exception {
        var channel = new EmbeddedChannel();
        var player = mock(Player.class);
        var transport = transport(player, channel);
        doThrow(new IllegalStateException("attach failed")).when(transport).attach(any(), any(), any(), any());
        login(transport, player, channel);
        verify(player).disconnect(VelocityTransport.REFUSED);
    }

    @Test
    void failedAttachInObserveOnlyLetsThePlayerJoin() throws Exception {
        var channel = new EmbeddedChannel();
        var player = mock(Player.class);
        var transport = transport(player, channel);
        doThrow(new IllegalStateException("attach failed")).when(transport).attach(any(), any(), any(), any());
        Object config =
                VelocityClientAdmissionTest.swap("configManager", VelocityClientAdmissionTest.observeOnly(true));
        try {
            login(transport, player, channel);
        } finally {
            VelocityClientAdmissionTest.swap("configManager", config);
        }
        verify(player, never()).disconnect(any());
    }

    @Test
    void playerWhoLeftDuringAttachIsNotDisconnected() throws Exception {
        var channel = new EmbeddedChannel();
        var player = mock(Player.class);
        var transport = transport(player, channel);
        doAnswer(invocation -> {
                    channel.close();
                    throw new IllegalStateException("channel closed");
                })
                .when(transport)
                .attach(any(), any(), any(), any());
        login(transport, player, channel);
        verify(player, never()).disconnect(any());
    }

    private static VelocityTransport transport(Player player, Channel channel) throws Exception {
        var transport = mock(VelocityTransport.class, CALLS_REAL_METHODS);
        set(transport, "running", true);
        set(transport, "bedrockOwners", (Function<Channel, ?>) ignored -> null);
        set(transport, "clients", mock(VelocityClientSupport.class));
        doReturn(channel).when(transport).channel(player);
        return transport;
    }

    private static void login(VelocityTransport transport, Player player, EmbeddedChannel channel) throws Exception {
        Field loader = CultAPI.class.getDeclaredField("loader");
        loader.setAccessible(true);
        Object originalLoader = loader.get(CultAPI.INSTANCE);
        loader.set(CultAPI.INSTANCE, mock(PlatformLoader.class, RETURNS_DEEP_STUBS));
        try {
            transport.login(new PostLoginEvent(player));
            channel.runPendingTasks();
        } finally {
            loader.set(CultAPI.INSTANCE, originalLoader);
            channel.finishAndReleaseAll();
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = VelocityTransport.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
