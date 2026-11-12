package ac.cult.cultac.bedrock.bridge;

import static org.junit.Assert.*;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.bedrock.player.BedrockPlayerState;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.PlatformConnection;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.TrackerData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
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
 * The latency marker that ends a batch of moves of other actors proves them, and the client takes each move whole when
 * it answers it. Before data that can use up the client's packet budget a marker proves the moves written so far; such
 * markers take at most two a client tick. A chunk column new to the client's chunk view is followed by its own marker.
 */
public final class GeyserEntityWaveMarkerTest {
    private static final long PLAYER = 123L;

    @Test
    public void passengerSeatDataAppliesOnlyAtItsAcknowledgedBoundary() throws Exception {
        try (Transport transport = new Transport()) {
            PacketEntity passenger = transport.track(456L, 900);
            transport.write(move(456L));
            transport.flush();
            assertTrue(transport.player.addTransactionResponse(transport.markers.getLast()));

            var metadata = new org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket();
            metadata.setRuntimeEntityId(456L);
            metadata.getMetadata()
                    .put(
                            org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes.SEAT_OFFSET,
                            Vector3f.from(0.2F, 0.3875F, 0));
            transport.write(metadata);
            transport.flush();
            assertEquals(2, transport.markers.size());
            assertEquals(Vec3.ZERO, passenger.bedrockSeatOffset);
            assertTrue(transport.player.addTransactionResponse(transport.markers.getLast()));
            assertEquals(new Vec3(0.2F, 0.3875F, 0), passenger.bedrockSeatOffset);

            // A partial size update keeps the independently written seat.
            var size = new org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket();
            size.setRuntimeEntityId(456L);
            size.getMetadata().put(org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes.WIDTH, 0.7F);
            transport.write(size);
            transport.flush();
            assertTrue(transport.player.addTransactionResponse(transport.markers.getLast()));
            assertEquals(new Vec3(0.2F, 0.3875F, 0), passenger.bedrockSeatOffset);
        }
    }

    @Test
    public void aBatchOfMovesEndsWithTheMarkerThatProvesThem() throws Exception {
        try (Transport transport = new Transport()) {
            transport.track(456L, 900);
            transport.track(457L, 901);
            transport.write(move(456L));
            transport.write(move(457L));
            assertEquals(List.of("move 456", "move 457", "marker"), transport.flush());
        }
    }

    @Test
    public void heavyDataGetsAMarkerAfterTheMovesBeforeIt() throws Exception {
        try (Transport transport = new Transport()) {
            transport.track(456L, 900);
            transport.track(457L, 901);
            transport.write(move(456L));
            transport.write(chunk());
            transport.write(move(457L));
            assertEquals(List.of("move 456", "marker", "chunk", "marker", "move 457", "marker"), transport.flush());
        }
    }

    @Test
    public void aTickTakesAtMostTwoMarkersBeforeHeavyData() throws Exception {
        try (Transport transport = new Transport()) {
            for (int i = 0; i < 4; i++) transport.track(456L + i, 900 + i);
            transport.write(move(456L));
            transport.write(chunk());
            transport.write(move(457L));
            transport.write(chunk());
            transport.write(move(458L));
            transport.write(chunk());
            transport.write(move(459L));
            assertEquals(
                    List.of(
                            "move 456",
                            "marker",
                            "chunk",
                            "marker",
                            "move 457",
                            "marker",
                            "chunk",
                            "move 458",
                            "chunk",
                            "move 459",
                            "marker"),
                    transport.flush());
            // The next client tick may narrow again.
            transport.player.compensatedEntities.onClientTick();
            transport.write(move(456L));
            transport.write(chunk());
            assertEquals(List.of("move 456", "marker", "chunk"), transport.flush());
        }
    }

    @Test
    public void thePlayersOwnMoveNeedsNoMarker() throws Exception {
        try (Transport transport = new Transport()) {
            transport.write(move(PLAYER));
            assertEquals(List.of("move 123"), transport.flush());
        }
    }

    @Test
    public void twoMovesOfOneActorProvenByOneMarkerAreHandledTogether() throws Exception {
        try (Transport transport = new Transport()) {
            PacketEntity zombie = transport.track(456L, 900);
            transport.write(move(456L, 3));
            transport.write(move(456L, -3));
            assertEquals(List.of("move 456", "move 456", "marker"), transport.flush());

            CultPlayer player = transport.player;
            assertTrue(player.addTransactionResponse(transport.markers.get(0)));
            zombie.onMovement(player, true);
            // One step from 0 toward -3: the move to 3 was replaced before any tick.
            assertSpans(player, zombie, -1.0D, -1.0D);
        }
    }

    @Test
    public void aMoveProvenBeforeHeavyDataStepsFromTheTickItsMarkerIsAnswered() throws Exception {
        try (Transport transport = new Transport()) {
            PacketEntity zombie = transport.track(456L, 900);
            // The zombie at x=0 is sent to 3 and back to -3, with data between that can use up the client's packet
            // budget.
            transport.write(move(456L, 3));
            transport.write(chunk());
            transport.write(move(456L, -3));
            assertEquals(List.of("move 456", "marker", "chunk", "marker", "move 456", "marker"), transport.flush());

            CultPlayer player = transport.player;
            assertTrue(player.addTransactionResponse(transport.markers.get(0)));
            zombie.onMovement(player, true);
            assertSpans(player, zombie, 1.0D, 1.0D);
            assertTrue(player.addTransactionResponse(transport.markers.get(2)));
            zombie.onMovement(player, true);
            // A third of the way from 1 to -3.
            assertSpans(player, zombie, -1.0D / 3.0D, -1.0D / 3.0D);
        }
    }

    // The client may show the actor's feet anywhere from fromX to toX.
    private static void assertSpans(CultPlayer player, PacketEntity entity, double fromX, double toX) {
        SimpleCollisionBox possible = entity.getPossibleMovementCollisionBoxes();
        SimpleCollisionBox atRest = GetBoundingBox.getPacketEntityBoundingBox(player, 0, 64, 0, entity);
        double halfWidth = (atRest.maxX - atRest.minX) / 2.0D;
        assertEquals(possible.toString(), fromX - halfWidth, possible.minX, 1.0E-6D);
        assertEquals(possible.toString(), toX + halfWidth, possible.maxX, 1.0E-6D);
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

    private static MoveEntityAbsolutePacket move(long runtimeId, float x) {
        var move = move(runtimeId);
        move.setPosition(Vector3f.from(x, 64, 0));
        return move;
    }

    private static final class Transport implements AutoCloseable {
        final EmbeddedChannel javaChannel = new EmbeddedChannel();
        final EmbeddedChannel wire = new EmbeddedChannel();
        final GeyserSession session = Mockito.mock(GeyserSession.class, Mockito.RETURNS_DEEP_STUBS);
        final Map<Object, Object> users;
        final Map<Object, Object> taps;
        final CultPlayer player;
        // The ids of the markers flushed so far, in order.
        final List<Integer> markers = new ArrayList<>();

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
            // Unless a test tracks an actor, no move is followed: only where the markers go is under test.
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

        // Translates runtimeId to a zombie at the origin that the player tracks, so its moves are followed.
        PacketEntity track(long runtimeId, int javaId) {
            var translated = Mockito.mock(org.geysermc.geyser.entity.type.Entity.class);
            Mockito.when(translated.getEntityId()).thenReturn(javaId);
            Mockito.when(session.getEntityCache().getEntityByGeyserId(runtimeId))
                    .thenReturn(translated);
            player.compensatedEntities.serverPositionsMap.put(
                    javaId, new TrackerData(0, 64, 0, 0, 0, EntityTypeIds.ZOMBIE, 0));
            player.compensatedEntities.addEntity(javaId, EntityTypeIds.ZOMBIE, new Vec3(0, 64, 0), 0, 0, 0);
            PacketEntity entity = player.compensatedEntities.getEntity(javaId);
            entity.bedrockRuntimeId = runtimeId;
            return entity;
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
                    if (packet instanceof NetworkStackLatencyPacket marker)
                        markers.add(Math.toIntExact(marker.getTimestamp()));
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
