package ac.cult.cultac.command.commands;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.bedrock.bridge.GeyserBedrockBridgeRuntime;
import ac.cult.cultac.bedrock.player.BedrockPlayerState;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.latency.GeyserQueue;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Map;
import java.util.UUID;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.BedrockSession;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket;
import org.geysermc.geyser.session.GeyserSession;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.*;

public final class CultDebugVelocityTest {
    @Test
    public void bedrockSilentWriteReachesWireWithoutTrackingAndNormalWriteStillTracks() throws Exception {
        try (Transport transport = new Transport()) {
            SetEntityMotionPacket packet = new SetEntityMotionPacket();
            packet.setRuntimeEntityId(123L);
            packet.setMotion(Vector3f.from(0.125F, 0.42F, -0.25F));
            var observer = CultDebugVelocity.BedrockVelocity.observerContext(transport.wire.pipeline());
            assertNotNull(observer);

            assertTrue(CultDebugVelocity.BedrockVelocity.writeSilently(observer, packet).isSuccess());
            transport.javaChannel.runPendingTasks();
            assertSame(packet, transport.read());
            assertNull(transport.wire.readOutbound());
            assertNull(transport.player.checkManager.getKnockbackHandler().lastSent);

            // Even this very same packet remains visible when sent through the normal pipeline.
            transport.wire.writeOutbound(BedrockPacketWrapper.create(0, 0, 0, packet, null));
            transport.javaChannel.runPendingTasks();
            assertSame(packet, transport.read());
            assertTrue(transport.read() instanceof NetworkStackLatencyPacket);
            assertNotNull(transport.player.checkManager.getKnockbackHandler().lastSent);
            assertNull(transport.wire.readOutbound());
        }
    }

    private static final class Transport implements AutoCloseable {
        final EmbeddedChannel javaChannel = new EmbeddedChannel();
        final EmbeddedChannel wire = new EmbeddedChannel();
        final GeyserSession session = Mockito.mock(GeyserSession.class, Mockito.RETURNS_DEEP_STUBS);
        final Map<Object, Object> players;
        final Map<Object, Object> users;
        final Map<Object, Object> taps;
        final CultPlayer player;

        Transport() throws Exception {
            OfflineCultTestBootstrap.installConfig();
            UUID uuid = UUID.randomUUID();
            User user = new User(new User.Profile(uuid, ".Velocity_Test"), null, null, null, javaChannel);
            player = new CultPlayer(user, MovementPlatform.BEDROCK, new BedrockPlayerState(uuid));
            players = map(CultAPI.INSTANCE.getPlayerDataManager(), "playerDataMap");
            users = map(CultAPI.INSTANCE.getNetworkManager(), "currentUsersByUuid");
            taps = map(null, GeyserBedrockBridgeRuntime.class, "PACKET_TAPS");
            players.put(user, player);
            users.put(uuid, user);
            Mockito.when(session.javaUuid()).thenReturn(uuid);
            Mockito.when(session.getPlayerEntity().geyserId()).thenReturn(123L);
            Mockito.when(session.getDownstream().getSession().getChannel().localAddress())
                    .thenReturn(javaChannel.unsafe().remoteAddress());

            Class<?> tapType = Class.forName(GeyserBedrockBridgeRuntime.class.getName() + "$PacketTapHandler");
            var tapConstructor = tapType.getDeclaredConstructor(GeyserSession.class, BedrockSession.class,
                    BedrockPacketHandler.class, GeyserQueue.class);
            tapConstructor.setAccessible(true);
            Object tap = tapConstructor.newInstance(session, null, null, new GeyserQueue());
            taps.put(session, tap);
            Class<?> outboundType = Class.forName(GeyserBedrockBridgeRuntime.class.getName() + "$OutboundPacketTap");
            var outboundConstructor = outboundType.getDeclaredConstructor(tapType);
            outboundConstructor.setAccessible(true);
            wire.pipeline().addLast((ChannelHandler) outboundConstructor.newInstance(tap));
        }

        BedrockPacket read() {
            BedrockPacketWrapper wrapper = wire.readOutbound();
            assertNotNull(wrapper);
            try {
                return wrapper.getPacket();
            } finally {
                wrapper.release();
            }
        }

        @Override
        public void close() {
            taps.remove(session);
            players.remove(player.user);
            users.remove(player.playerUUID, player.user);
            wire.finishAndReleaseAll();
            javaChannel.finishAndReleaseAll();
        }
    }

    private static Map<Object, Object> map(Object owner, String name) throws Exception {
        return map(owner, owner.getClass(), name);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> map(Object owner, Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<Object, Object>) field.get(owner);
    }
}
