package ac.cult.cultac.platform.velocity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.manager.config.BaseConfigManager;
import ac.cult.cultac.platform.api.PlatformLoader;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VelocityClientAdmissionTest {
    private Object originalLoader;

    @BeforeEach
    void installLoader() throws ReflectiveOperationException {
        originalLoader = swap("loader", mock(PlatformLoader.class, RETURNS_DEEP_STUBS));
    }

    @AfterEach
    void restoreLoader() throws ReflectiveOperationException {
        swap("loader", originalLoader);
    }

    @ParameterizedTest
    @ValueSource(ints = {47, 340, 763, 764, 766, 767})
    void outdatedClientsWithoutViaAreDisconnectedBeforeProtocolResolution(int protocol) {
        Player player = player(protocol);
        var channel = new EmbeddedChannel();
        try {
            assertTrue(VelocityProtocols.disconnectUnsupportedClient(player, channel));
            verify(player).disconnect(Component.text("Outdated client! Please use Minecraft 1.21.2 or newer."));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {768, 769, 770, 771, 772, 773, 774})
    void clientsFrom1212OnwardAreAdmitted(int protocol) {
        Player player = player(protocol);
        var channel = new EmbeddedChannel();
        try {
            assertFalse(VelocityProtocols.disconnectUnsupportedClient(player, channel));
            verify(player, never()).disconnect(any(Component.class));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {47, 340, 763, 764, 766, 767})
    void outdatedClientsBehindViaAreDisconnected(int original) {
        Player player = player(774);
        var channel = viaChannel(original);
        try {
            assertTrue(VelocityProtocols.disconnectUnsupportedClient(player, channel));
            verify(player).disconnect(Component.text("Outdated client! Please use Minecraft 1.21.2 or newer."));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {768, 774})
    void supportedClientsBehindViaAreAdmitted(int original) {
        Player player = player(774);
        var channel = viaChannel(original);
        try {
            assertFalse(VelocityProtocols.disconnectUnsupportedClient(player, channel));
            verify(player, never()).disconnect(any(Component.class));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void newerClientsBehindViaAreRefused() {
        Player player = player(774);
        var channel = viaChannel(VelocityProtocols.NEWEST.protocol() + 1);
        try {
            assertTrue(VelocityProtocols.disconnectUnsupportedClient(player, channel));
            verify(player).disconnect(VelocityTransport.REFUSED);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void observeOnlyLetsUnsupportedClientsJoinUninspected() throws ReflectiveOperationException {
        Object config = swap("configManager", observeOnly(true));
        try {
            assertJoinsUninspected(player(767), new EmbeddedChannel());
            assertJoinsUninspected(player(774), viaChannel(VelocityProtocols.NEWEST.protocol() + 1));
        } finally {
            swap("configManager", config);
        }
    }

    private static void assertJoinsUninspected(Player player, EmbeddedChannel channel) {
        try {
            assertTrue(VelocityProtocols.disconnectUnsupportedClient(player, channel));
            verify(player, never()).disconnect(any(Component.class));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    static BaseConfigManager observeOnly(boolean observeOnly) {
        return new BaseConfigManager() {
            @Override
            public boolean isObserveOnly() {
                return observeOnly;
            }
        };
    }

    static Object swap(String name, Object value) throws ReflectiveOperationException {
        Field field = CultAPI.class.getDeclaredField(name);
        field.setAccessible(true);
        Object original = field.get(CultAPI.INSTANCE);
        field.set(CultAPI.INSTANCE, value);
        return original;
    }

    private static EmbeddedChannel viaChannel(int original) {
        var channel = new EmbeddedChannel();
        var connection = new ViaConnection(channel, false, new ViaInfo(new ViaVersion(original)));
        channel.pipeline().addLast("via-decoder", new ViaCodec(connection));
        channel.pipeline().addLast("via-encoder", new ViaCodec(connection));
        return channel;
    }

    record ViaVersion(int getOriginalVersion) {}

    record ViaInfo(ViaVersion protocolVersion) {}

    record ViaConnection(Channel getChannel, boolean isClientSide, ViaInfo getProtocolInfo) {}

    static final class ViaCodec extends ChannelDuplexHandler {
        private final ViaConnection connection;

        ViaCodec(ViaConnection connection) {
            this.connection = connection;
        }

        public ViaConnection connection() {
            return connection;
        }
    }

    private static Player player(int protocol) {
        Player player = mock(Player.class);
        when(player.getProtocolVersion()).thenReturn(ProtocolVersion.getProtocolVersion(protocol));
        return player;
    }
}
