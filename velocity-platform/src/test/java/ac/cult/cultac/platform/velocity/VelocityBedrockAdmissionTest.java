package ac.cult.cultac.platform.velocity;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.platform.api.PlatformLoader;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VelocityBedrockAdmissionTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bedrockPlayerWithoutBridgeIsDisconnectedUnlessObserveOnly(boolean observeOnly) throws Exception {
        var proxy = mock(ProxyServer.class);
        var plugins = mock(PluginManager.class);
        when(proxy.getPluginManager()).thenReturn(plugins);
        when(plugins.getPlugin("geyser")).thenReturn(Optional.empty());
        when(plugins.getPlugin("floodgate")).thenReturn(Optional.empty());
        var platform = mock(VelocityPlatform.class, CALLS_REAL_METHODS);
        Field field = VelocityPlatform.class.getDeclaredField("proxy");
        field.setAccessible(true);
        field.set(platform, proxy);
        var player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.fromString("00000000-0000-0000-0009-000000000001"));
        var channel = new EmbeddedChannel();
        Field loader = CultAPI.class.getDeclaredField("loader");
        loader.setAccessible(true);
        Object originalLoader = loader.get(CultAPI.INSTANCE);
        loader.set(CultAPI.INSTANCE, mock(PlatformLoader.class, RETURNS_DEEP_STUBS));
        Object config =
                VelocityClientAdmissionTest.swap("configManager", VelocityClientAdmissionTest.observeOnly(observeOnly));
        try {
            platform.attach(player, channel);
            verify(player, times(observeOnly ? 0 : 1)).disconnect(VelocityTransport.REFUSED);
        } finally {
            VelocityClientAdmissionTest.swap("configManager", config);
            loader.set(CultAPI.INSTANCE, originalLoader);
            channel.finishAndReleaseAll();
        }
    }
}
