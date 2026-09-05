package ac.cult.cultac.network;

import ac.cult.cultac.network.event.PacketListenerPriority;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.AttributeKey;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CultNetworkManagerBundleSilencingTest {
    @Test
    public void bundleSilencingTracksOnlyObservedLeavesByIdentity() throws Exception {
        CultNetworkManager manager = new CultNetworkManager();
        manager.registerSendHandler(ClientboundPingPacket.class, PacketListenerPriority.NORMAL,
                (event, user, packet) -> {
                });

        ClientboundPingPacket observed = new ClientboundPingPacket(7);
        ClientboundKeepAlivePacket unobserved = new ClientboundKeepAlivePacket(8L);
        ClientboundBundlePacket bundle = new ClientboundBundlePacket(List.of(observed, unobserved));
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            Method add = CultNetworkManager.class.getDeclaredMethod(
                    "addSilentOutboundPacket", io.netty.channel.Channel.class, Packet.class);
            add.setAccessible(true);
            add.invoke(manager, channel, bundle);

            Field keyField = CultNetworkManager.class.getDeclaredField("SILENT_OUTBOUND");
            keyField.setAccessible(true);
            @SuppressWarnings("unchecked")
            AttributeKey<CopyOnWriteArrayList<Packet<?>>> key =
                    (AttributeKey<CopyOnWriteArrayList<Packet<?>>>) keyField.get(null);

            Method consume = CultNetworkManager.class.getDeclaredMethod(
                    "consumeSilentPacket", io.netty.channel.Channel.class, AttributeKey.class, Packet.class);
            consume.setAccessible(true);

            assertFalse((boolean) consume.invoke(null, channel, key, bundle));
            assertFalse((boolean) consume.invoke(null, channel, key, unobserved));
            assertTrue((boolean) consume.invoke(null, channel, key, observed));
            assertFalse((boolean) consume.invoke(null, channel, key, observed));
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
