package ac.cult.cultac.network.protocol.util.viaversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.viaversion.viaversion.api.connection.ProtocolInfo;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.platform.ViaDecodeHandler;
import com.viaversion.viaversion.platform.ViaEncodeHandler;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

class ViaConnectionProtocolTest {
    @Test
    void readsTheOriginalProtocolThroughAWrappedChannel() {
        EmbeddedChannel physical = new EmbeddedChannel();
        install(physical, translator(physical, ProtocolVersion.v1_21));

        assertEquals(ProtocolVersion.v1_21.getOriginalVersion(), ViaConnectionProtocol.originalProtocol(physical));
        assertEquals(ProtocolVersion.v1_21.getOriginalVersion(), ViaConnectionProtocol.originalProtocol(wrap(physical)));
    }

    @Test
    void rejectsCodecsOfAnotherConnection() {
        EmbeddedChannel physical = new EmbeddedChannel();
        install(physical, translator(new EmbeddedChannel(), ProtocolVersion.v1_21));

        assertThrows(IllegalStateException.class, () -> ViaConnectionProtocol.originalProtocol(wrap(physical)));
    }

    @Test
    void reportsNoTranslator() {
        assertEquals(-1, ViaConnectionProtocol.originalProtocol(wrap(new EmbeddedChannel())));
    }

    private static UserConnection translator(Channel channel, ProtocolVersion original) {
        ProtocolInfo info = mock(ProtocolInfo.class);
        when(info.protocolVersion()).thenReturn(original);
        UserConnection user = mock(UserConnection.class);
        when(user.getChannel()).thenReturn(channel);
        when(user.getProtocolInfo()).thenReturn(info);
        return user;
    }

    private static void install(Channel channel, UserConnection user) {
        channel.pipeline().addLast("via-encoder", new ViaEncodeHandler(user));
        channel.pipeline().addLast("via-decoder", new ViaDecodeHandler(user));
    }

    /** Delegates like ProtocolLib's NettyChannelProxy: a distinct identity sharing the physical pipeline. */
    private static Channel wrap(Channel delegate) {
        return (Channel) Proxy.newProxyInstance(
                Channel.class.getClassLoader(), new Class<?>[] {Channel.class}, (proxy, method, args) -> {
                    if (method.getName().equals("equals")) return proxy == args[0];
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }
}
