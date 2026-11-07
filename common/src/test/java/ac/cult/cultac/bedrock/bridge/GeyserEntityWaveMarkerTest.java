package ac.cult.cultac.bedrock.bridge;

import static org.junit.Assert.*;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.bedrock.player.BedrockPlayerState;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.PlatformConnection;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.reload.integrations.GeyserQueue;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.BedrockSession;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityAbsolutePacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket;
import org.geysermc.geyser.session.GeyserSession;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * A move of another actor follows a latency marker in its own batch, with no heavy packet in between, so the client
 * normally answers the marker and handles the move before the same tick. Markers written only for that take at most two
 * a client tick.
 */
public final class GeyserEntityWaveMarkerTest {
    private static final long PLAYER = 123L;

    @Test
    public void aWaveOfMovesOpensWithAMarkerInItsBatch() throws Exception {
        try (Transport transport = new Transport()) {
            transport.write(move(456L));
            transport.write(move(457L));
            assertEquals(List.of("marker", "move 456", "move 457"), transport.flush());
            // The marker before belongs to the batch already sent.
            transport.write(move(456L));
            assertEquals(List.of("marker", "move 456"), transport.flush());
        }
    }

    @Test
    public void heavyDataEndsTheWaveSoTheNextMoveOpensAnother() throws Exception {
        try (Transport transport = new Transport()) {
            transport.write(move(456L));
            transport.write(chunk());
            transport.write(move(457L));
            assertEquals(List.of("marker", "move 456", "chunk", "marker", "move 457"), transport.flush());
        }
    }

    @Test
    public void aTickTakesAtMostTwoMarkersOnlyToNarrowWaves() throws Exception {
        try (Transport transport = new Transport()) {
            transport.write(move(456L));
            transport.write(chunk());
            transport.write(move(457L));
            transport.write(chunk());
            transport.write(move(458L));
            assertEquals(
                    List.of("marker", "move 456", "chunk", "marker", "move 457", "chunk", "move 458"),
                    transport.flush());
            // The next client tick may narrow again.
            transport.player.compensatedEntities.onClientTick();
            transport.write(move(456L));
            assertEquals(List.of("marker", "move 456"), transport.flush());
        }
    }

    @Test
    public void thePlayersOwnMoveNeedsNoMarker() throws Exception {
        try (Transport transport = new Transport()) {
            transport.write(move(PLAYER));
            assertEquals(List.of("move 123"), transport.flush());
        }
    }

    private static LevelChunkPacket chunk() {
        var chunk = new LevelChunkPacket();
        chunk.setData(io.netty.buffer.Unpooled.buffer(1));
        return chunk;
    }

    private static MoveEntityAbsolutePacket move(long runtimeId) {
        var move = new MoveEntityAbsolutePacket();
        move.setRuntimeEntityId(runtimeId);
        move.setPosition(Vector3f.from(1, 64, 1));
        move.setRotation(Vector3f.ZERO);
        return move;
    }

    private static final class Transport implements AutoCloseable {
        final EmbeddedChannel javaChannel = new EmbeddedChannel();
        final EmbeddedChannel wire = new EmbeddedChannel();
        final GeyserSession session = Mockito.mock(GeyserSession.class, Mockito.RETURNS_DEEP_STUBS);
        final Map<Object, Object> users;
        final Map<Object, Object> taps;
        final CultPlayer player;

        Transport() throws Exception {
            OfflineCultTestBootstrap.installConfig();
            UUID uuid = UUID.randomUUID();
            PlatformConnection platform = Mockito.mock(PlatformConnection.class);
            Mockito.doAnswer(invocation -> {
                        invocation.<Runnable>getArgument(0).run();
                        return null;
                    })
                    .when(platform)
                    .runInModel(Mockito.any(Runnable.class));
            User user = ac.cult.cultac.network.TestUsers.create(
                    new User.Profile(uuid, ".Wave_Test"), javaChannel, platform);
            player = new CultPlayer(user, MovementPlatform.BEDROCK, new BedrockPlayerState(uuid));
            var manager = CultAPI.INSTANCE.getNetworkManager();
            var connections = manager.getClass().getDeclaredField("connections");
            connections.setAccessible(true);
            users = map(connections.get(manager), "currentByUuid");
            taps = map(null, GeyserBedrockBridgeRuntime.class, "PACKET_TAPS");
            synchronized (user.getCultConnection()) {
                user.getCultConnection().player(player);
            }
            users.put(uuid, user.getCultConnection());
            Mockito.when(session.javaUuid()).thenReturn(uuid);
            Mockito.when(session.getPlayerEntity().geyserId()).thenReturn(PLAYER);
            Mockito.when(session.getDownstream().getSession().getChannel().localAddress())
                    .thenReturn(javaChannel.unsafe().remoteAddress());
            // No actor is translated, so no move is tracked: only where the markers go is under test.
            Mockito.when(session.getEntityCache().getEntityByGeyserId(Mockito.anyLong()))
                    .thenReturn(null);

            Class<?> tapType = Class.forName(GeyserBedrockBridgeRuntime.class.getName() + "$PacketTapHandler");
            var tapConstructor = tapType.getDeclaredConstructor(
                    GeyserSession.class, BedrockSession.class, BedrockPacketHandler.class, GeyserQueue.class);
            tapConstructor.setAccessible(true);
            Object tap = tapConstructor.newInstance(session, null, null, new GeyserQueue());
            taps.put(session, tap);
            Class<?> outboundType = Class.forName(GeyserBedrockBridgeRuntime.class.getName() + "$OutboundPacketTap");
            var outboundConstructor = outboundType.getDeclaredConstructor(tapType);
            outboundConstructor.setAccessible(true);
            wire.pipeline().addLast((ChannelHandler) outboundConstructor.newInstance(tap));
        }

        void write(BedrockPacket packet) {
            wire.write(BedrockPacketWrapper.create(0, 0, 0, packet, null));
        }

        // Flushes the batch and names what it carried, in order.
        List<String> flush() {
            wire.flush();
            List<String> batch = new ArrayList<>();
            BedrockPacketWrapper wrapper;
            while ((wrapper = wire.readOutbound()) != null) {
                try {
                    BedrockPacket packet = wrapper.getPacket();
                    batch.add(
                            packet instanceof NetworkStackLatencyPacket
                                    ? "marker"
                                    : packet instanceof MoveEntityAbsolutePacket move
                                            ? "move " + move.getRuntimeEntityId()
                                            : packet instanceof LevelChunkPacket ? "chunk" : packet.toString());
                } finally {
                    wrapper.release();
                }
            }
            return batch;
        }

        @Override
        public void close() {
            taps.remove(session);
            synchronized (player.user.getCultConnection()) {
                player.user.getCultConnection().player(null);
            }
            users.remove(player.playerUUID, player.user.getCultConnection());
            wire.finishAndReleaseAll();
            javaChannel.finishAndReleaseAll();
        }

        @SuppressWarnings("unchecked")
        private static Map<Object, Object> map(Object owner, String field) throws Exception {
            var declared = owner.getClass().getDeclaredField(field);
            declared.setAccessible(true);
            return (Map<Object, Object>) declared.get(owner);
        }

        @SuppressWarnings("unchecked")
        private static Map<Object, Object> map(Object owner, Class<?> type, String field) throws Exception {
            var declared = type.getDeclaredField(field);
            declared.setAccessible(true);
            return (Map<Object, Object>) declared.get(owner);
        }
    }
}
