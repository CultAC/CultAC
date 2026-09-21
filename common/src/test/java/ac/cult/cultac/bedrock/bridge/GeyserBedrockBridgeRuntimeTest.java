package ac.cult.cultac.bedrock.bridge;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.NoSuchElementException;
import org.cloudburstmc.math.vector.Vector3f;
import org.junit.Test;
import org.mockito.Mockito;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class GeyserBedrockBridgeRuntimeTest {

    @Test
    public void invalidMovementIsRejectedBeforeFrameCapture() {
        var packet = new PlayerAuthInputPacket();
        packet.setPosition(org.cloudburstmc.math.vector.Vector3f.ZERO);
        packet.setDelta(org.cloudburstmc.math.vector.Vector3f.ZERO);
        packet.setRotation(org.cloudburstmc.math.vector.Vector3f.ZERO);
        packet.setMotion(org.cloudburstmc.math.vector.Vector2f.ZERO);
        assertTrue(GeyserBedrockBridgeRuntime.hasFiniteMovement(packet));
        packet.setPosition(org.cloudburstmc.math.vector.Vector3f.from(Float.NaN, 64, 0));
        assertFalse(GeyserBedrockBridgeRuntime.hasFiniteMovement(packet));
        packet.setPosition(org.cloudburstmc.math.vector.Vector3f.ZERO);
        packet.setDelta(org.cloudburstmc.math.vector.Vector3f.from(0, Float.POSITIVE_INFINITY, 0));
        assertFalse(GeyserBedrockBridgeRuntime.hasFiniteMovement(packet));
    }

    @Test
    public void simulatedCollisionsReplaceBothForgedAndMissingClientFlags() {
        var packet = new PlayerAuthInputPacket();
        packet.getInputData().add(PlayerAuthInputData.VERTICAL_COLLISION);
        packet.getInputData().add(PlayerAuthInputData.HORIZONTAL_COLLISION);
        packet.getInputData().add(PlayerAuthInputData.PERFORM_ITEM_STACK_REQUEST);
        GeyserBedrockBridgeRuntime.correctCollisions(packet,
                ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags.AIR);
        assertFalse(packet.getInputData().contains(PlayerAuthInputData.VERTICAL_COLLISION));
        assertFalse(packet.getInputData().contains(PlayerAuthInputData.HORIZONTAL_COLLISION));
        assertTrue(packet.getInputData().contains(PlayerAuthInputData.PERFORM_ITEM_STACK_REQUEST));
        GeyserBedrockBridgeRuntime.correctCollisions(packet,
                ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags.ON_GROUND);
        assertTrue(packet.getInputData().contains(PlayerAuthInputData.VERTICAL_COLLISION));
    }

    @Test
    public void absentVehicleSectionCannotSelectVehicleCoordinates() {
        var packet = new PlayerAuthInputPacket();
        assertEquals(-1L, GeyserBedrockBridgeRuntime.predictedVehicleId(packet));
        packet.setPredictedVehicle(146L);
        assertEquals(-1L, GeyserBedrockBridgeRuntime.predictedVehicleId(packet));
        packet.getInputData().add(PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE);
        assertEquals(146L, GeyserBedrockBridgeRuntime.predictedVehicleId(packet));
    }

    @Test
    public void predictedEquinesDoNotRequireDefinitionAccessor() {
        assertFalse(GeyserBedrockBridgeRuntime.isPredictedHorse(null));
        for (var type : List.of(
                org.geysermc.geyser.entity.type.living.animal.horse.HorseEntity.class,
                org.geysermc.geyser.entity.type.living.animal.horse.ChestedHorseEntity.class,
                org.geysermc.geyser.entity.type.living.animal.horse.SkeletonHorseEntity.class,
                org.geysermc.geyser.entity.type.living.animal.horse.ZombieHorseEntity.class)) {
            var entity = Mockito.mock(type);
            assertTrue(GeyserBedrockBridgeRuntime.isPredictedHorse(entity));
            Mockito.verifyZeroInteractions(entity);
        }
        for (var type : List.of(
                org.geysermc.geyser.entity.type.living.animal.horse.LlamaEntity.class,
                org.geysermc.geyser.entity.type.living.animal.horse.TraderLlamaEntity.class,
                org.geysermc.geyser.entity.type.living.animal.horse.CamelEntity.class,
                org.geysermc.geyser.entity.type.living.animal.horse.CamelHuskEntity.class,
                org.geysermc.geyser.entity.type.BoatEntity.class)) {
            var entity = Mockito.mock(type);
            assertFalse(GeyserBedrockBridgeRuntime.isPredictedHorse(entity));
            Mockito.verifyZeroInteractions(entity);
        }
    }

    @Test
    public void latencyQueueInstallsThroughTheUnmodifiedGeyserGetter() throws Exception {
        var session = Mockito.mock(org.geysermc.geyser.session.GeyserSession.class, Mockito.CALLS_REAL_METHODS);
        var cache = org.geysermc.geyser.session.GeyserSession.class.getDeclaredField("latencyPingCache");
        cache.setAccessible(true);
        cache.set(session, new java.util.concurrent.ConcurrentLinkedQueue<Runnable>());
        var queue = GeyserBedrockBridgeRuntime.installLatencyQueue(session);
        assertSame(queue, session.getLatencyPingCache());
        assertSame(queue, GeyserBedrockBridgeRuntime.installLatencyQueue(session));
        assertTrue(queue.isEmpty());
    }

    @Test
    public void nativeReceiptsDoNotEmitJavaPongs() throws Exception {
        var session = Mockito.mock(org.geysermc.geyser.session.GeyserSession.class, Mockito.CALLS_REAL_METHODS);
        var cache = org.geysermc.geyser.session.GeyserSession.class.getDeclaredField("latencyPingCache");
        cache.setAccessible(true);
        cache.set(session, new java.util.concurrent.ConcurrentLinkedQueue<Runnable>());
        var queue = GeyserBedrockBridgeRuntime.installLatencyQueue(session);
        var geyser = Mockito.mock(org.geysermc.geyser.GeyserImpl.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.doReturn(geyser).when(session).getGeyser();
        Mockito.when(geyser.config().gameplay().forwardPlayerPing()).thenReturn(true);
        List<org.cloudburstmc.protocol.bedrock.packet.BedrockPacket> packets = new ArrayList<>();
        Mockito.doAnswer(invocation -> { packets.add(invocation.getArgument(0)); return null; })
                .when(session).sendUpstreamPacket(Mockito.any());
        Deque<Runnable> eventLoop = new ArrayDeque<>();
        Mockito.doAnswer(invocation -> { eventLoop.add(invocation.getArgument(0)); return null; })
                .when(session).ensureInEventLoop(Mockito.any(Runnable.class));
        List<Integer> pongs = new ArrayList<>();
        Mockito.doAnswer(invocation -> {
            var pong = (org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundPongPacket) invocation.getArgument(0);
            pongs.add(pong.getId());
            return null;
        }).when(session).sendDownstreamPacket(Mockito.any());

        var pingTranslator = new org.geysermc.geyser.translator.protocol.java.JavaPingTranslator();
        pingTranslator.translate(session, new org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundPingPacket(101));
        pingTranslator.translate(session, new org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundPingPacket(102));
        assertEquals(2, packets.size());
        queue.write(() -> {});
        queue.insert(GeyserBedrockBridgeRuntime.nativeLatencyCallback(session, 999), () -> {});
        queue.write(() -> {});
        var translator = new org.geysermc.geyser.translator.protocol.bedrock.BedrockNetworkStackLatencyTranslator();
        var reply = new org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket();
        reply.setTimestamp(0);
        for (int i = 0; i < 3; i++) translator.translate(session, reply);
        assertTrue(pongs.isEmpty());
        while (!eventLoop.isEmpty()) eventLoop.removeFirst().run();
        assertEquals(List.of(101, 102), pongs);
        assertTrue(queue.isEmpty());
    }

    @Test
    public void mixedLatencyCallbacksFollowWireOrderRegardlessOfTimestamp() {
        var queue = new ac.cult.cultac.utils.latency.GeyserQueue();
        var session = Mockito.mock(org.geysermc.geyser.session.GeyserSession.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(session.getLatencyPingCache()).thenReturn(queue);
        List<String> completed = new ArrayList<>();
        // Geyser registers these before Cloudburst flushes their packets.
        queue.add(() -> completed.add("ping-A"));
        queue.add(() -> completed.add("keepalive"));
        queue.add(() -> completed.add("inventory"));
        queue.add(() -> completed.add("form"));
        queue.add(() -> completed.add("ping-B"));
        assertNull(queue.poll());
        queue.write(() -> {});
        queue.insert(() -> completed.add("motion-A"), () -> {});
        queue.write(() -> {});
        queue.insert(() -> completed.add("motion-B"), () -> {});
        queue.write(() -> {});
        queue.write(() -> {});
        queue.write(() -> {});

        var translator = new org.geysermc.geyser.translator.protocol.bedrock.BedrockNetworkStackLatencyTranslator();
        for (long timestamp : new long[] {0, 0, Long.MIN_VALUE, Long.MAX_VALUE, -1, 42, 42, 42}) {
            var reply = new org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket();
            reply.setTimestamp(timestamp);
            translator.translate(session, reply);
        }
        assertEquals(List.of("ping-A", "motion-A", "keepalive", "motion-B", "inventory", "form", "ping-B"), completed);
        assertTrue(queue.isEmpty());
    }

    @Test
    public void nativeReceiptDoesNotForwardSyntheticPong() {
        var session = Mockito.mock(org.geysermc.geyser.session.GeyserSession.class);
        Deque<Runnable> eventLoop = new ArrayDeque<>();
        Mockito.doAnswer(invocation -> { eventLoop.add(invocation.getArgument(0)); return null; })
                .when(session).ensureInEventLoop(Mockito.any(Runnable.class));
        GeyserBedrockBridgeRuntime.nativeLatencyCallback(session, 123).run();
        Mockito.verify(session, Mockito.never()).sendDownstreamPacket(Mockito.any());
        eventLoop.removeFirst().run();
        Mockito.verify(session, Mockito.never()).sendDownstreamPacket(Mockito.any());
    }

    @Test
    public void failedWriteLeavesCallbackUnconfirmedAndDetachPreservesGeyserCallbacks() {
        var queue = new ac.cult.cultac.utils.latency.GeyserQueue();
        Runnable callback = () -> {};
        queue.add(callback);
        org.junit.Assert.assertThrows(IllegalStateException.class,
                () -> queue.write(() -> { throw new IllegalStateException("write failed"); }));
        assertNull(queue.poll());
        queue.stopTrackingWrites();
        assertSame(callback, queue.poll());
        queue.add(callback);
        assertSame(callback, queue.poll());
        queue.clear();
        assertTrue(queue.isEmpty());
    }

    @Test
    public void javaUserBindsToExactGeyserDownstreamSocket() {
        io.netty.channel.Channel geyserDownstream = Mockito.mock(io.netty.channel.Channel.class);
        io.netty.channel.Channel matchingServer = Mockito.mock(io.netty.channel.Channel.class);
        io.netty.channel.Channel otherServer = Mockito.mock(io.netty.channel.Channel.class);
        InetSocketAddress endpoint = new InetSocketAddress("127.0.0.1", 32145);

        Mockito.when(geyserDownstream.localAddress()).thenReturn(endpoint);
        var matchingUnsafe = Mockito.mock(io.netty.channel.Channel.Unsafe.class);
        var otherUnsafe = Mockito.mock(io.netty.channel.Channel.Unsafe.class);
        Mockito.when(matchingServer.unsafe()).thenReturn(matchingUnsafe);
        Mockito.when(otherServer.unsafe()).thenReturn(otherUnsafe);
        Mockito.when(matchingUnsafe.remoteAddress()).thenReturn(endpoint);
        Mockito.when(otherUnsafe.remoteAddress())
                .thenReturn(new InetSocketAddress("127.0.0.1", 32146));
        // Two players behind the same IP still have distinct native local peers.
        Mockito.when(matchingServer.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));
        Mockito.when(otherServer.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));

        assertTrue(GeyserBedrockBridgeRuntime.sameJavaConnection(
                geyserDownstream, matchingServer));
        assertFalse(GeyserBedrockBridgeRuntime.sameJavaConnection(
                geyserDownstream, otherServer));
        assertFalse(GeyserBedrockBridgeRuntime.sameJavaConnection(
                geyserDownstream, new Object()));
    }

    @Test
    public void unboundChannelsCannotMatchAnotherSession() {
        var downstream = Mockito.mock(io.netty.channel.Channel.class);
        var server = Mockito.mock(io.netty.channel.Channel.class);
        assertFalse(GeyserBedrockBridgeRuntime.sameJavaConnection(downstream, server));
    }

    @Test
    public void channelCloseRaceMakesTapRemovalIdempotent() {
        ChannelPipeline pipeline = Mockito.mock(ChannelPipeline.class);
        ChannelHandler handler = Mockito.mock(ChannelHandler.class);
        Mockito.when(pipeline.get("tap")).thenReturn(handler);
        Mockito.when(pipeline.remove("tap")).thenThrow(new NoSuchElementException("tap"));

        GeyserBedrockBridgeRuntime.removeHandlerIfPresent(pipeline, "tap");

        Mockito.verify(pipeline).remove("tap");
    }

    @Test
    public void everyClientVisibleMovePlayerTeleportCreatesPositionBoundary() {
        assertTrue(GeyserBedrockBridgeRuntime.isGeyserPositionTeleport(
                MovePlayerPacket.Mode.RESPAWN));
        assertTrue(GeyserBedrockBridgeRuntime.isGeyserPositionTeleport(
                MovePlayerPacket.Mode.TELEPORT));
        assertFalse(GeyserBedrockBridgeRuntime.isGeyserPositionTeleport(
                MovePlayerPacket.Mode.NORMAL));
    }

    @Test
    public void selfNormalCorrectionIsConvertedToOwnedTeleportBoundary() {
        MovePlayerPacket packet = new MovePlayerPacket();
        packet.setMode(MovePlayerPacket.Mode.NORMAL);

        GeyserBedrockBridgeRuntime.convertSelfCorrectionToTeleport(packet);

        assertEquals(MovePlayerPacket.Mode.TELEPORT, packet.getMode());
        assertEquals(MovePlayerPacket.TeleportationCause.BEHAVIOR, packet.getTeleportationCause());
    }

    @Test
    public void geyserSelfEntityTeleportPositionIsConvertedToPhysicalFeet() {
        Vector3f packetPosition = Vector3f.from(223.7F, 67.1152F, 416.82675F);

        net.minecraft.world.phys.Vec3 physicalFeet =
                GeyserBedrockBridgeRuntime.toJavaPosition(packetPosition);

        assertEquals((double) packetPosition.getX(), physicalFeet.x, 0.0D);
        assertEquals((double) packetPosition.getY() - 1.6200103759765625D,
                physicalFeet.y, 0.0D);
        assertEquals((double) packetPosition.getZ(), physicalFeet.z, 0.0D);
    }

}
