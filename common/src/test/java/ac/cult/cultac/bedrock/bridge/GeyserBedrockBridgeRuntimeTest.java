package ac.cult.cultac.bedrock.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import ac.cult.reload.integrations.GeyserQueue;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.NoSuchElementException;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.geysermc.geyser.session.GeyserSession;
import org.junit.Test;
import org.mockito.Mockito;

public final class GeyserBedrockBridgeRuntimeTest {

    @Test
    public void continuedActorCreationCannotAcceptAnOldAuthTick() throws Exception {
        var session = Mockito.mock(org.geysermc.geyser.session.GeyserSession.class);
        var type = Class.forName(GeyserBedrockBridgeRuntime.class.getName() + "$PacketTapHandler");
        var constructor = type.getDeclaredConstructor(
                org.geysermc.geyser.session.GeyserSession.class,
                org.cloudburstmc.protocol.bedrock.BedrockSession.class,
                org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler.class,
                ac.cult.reload.integrations.GeyserQueue.class);
        constructor.setAccessible(true);
        var tap = constructor.newInstance(session, null, null, new ac.cult.reload.integrations.GeyserQueue());
        var runtime = type.getDeclaredField("actorCreationRuntimeId");
        runtime.setAccessible(true);
        runtime.set(tap, 42L);
        var floor = type.getDeclaredField("continuedMovementTick");
        floor.setAccessible(true);
        floor.set(tap, 500L);
        var player = Mockito.mock(ac.cult.cultac.player.CultPlayer.class);
        player.bedrockState = new ac.cult.cultac.bedrock.player.BedrockPlayerState(java.util.UUID.randomUUID());
        player.checkManager = Mockito.mock(ac.cult.cultac.manager.player.CheckManager.class);
        var processor = Mockito.mock(ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor.class);
        Mockito.when(player.checkManager.getSimulationProcessor()).thenReturn(processor);
        Mockito.doAnswer(call -> {
                    player.bedrockState.recordActorCreation(call.getArgument(0));
                    return null;
                })
                .when(processor)
                .handleBedrockActorCreation(42L);
        var initialize = type.getDeclaredMethod("initializeActor", ac.cult.cultac.player.CultPlayer.class);
        initialize.setAccessible(true);
        initialize.invoke(tap, player);
        assertFalse(player.bedrockState.acceptMovementTick(500));
        assertFalse(player.bedrockState.acceptMovementTick(499));
        assertTrue(player.bedrockState.acceptMovementTick(501));
        initialize.invoke(tap, player);
        assertEquals(501, player.bedrockState.lastMovementTick());
    }

    @Test
    public void queuedLatencyReplyReachesReplacementTapInItsOriginalTickLoopSlot() throws Exception {
        var session = Mockito.mock(org.geysermc.geyser.session.GeyserSession.class);
        var upstream = Mockito.mock(org.cloudburstmc.protocol.bedrock.BedrockSession.class);
        var replacement = Mockito.mock(org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler.class);
        Mockito.when(upstream.getPacketHandler()).thenReturn(replacement);
        Deque<Runnable> accepted = new ArrayDeque<>();
        Mockito.doAnswer(call -> {
                    accepted.add(call.getArgument(0));
                    return null;
                })
                .when(session)
                .ensureInEventLoop(Mockito.any(Runnable.class));
        var type = Class.forName(GeyserBedrockBridgeRuntime.class.getName() + "$PacketTapHandler");
        var constructor = type.getDeclaredConstructor(
                org.geysermc.geyser.session.GeyserSession.class,
                org.cloudburstmc.protocol.bedrock.BedrockSession.class,
                org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler.class,
                ac.cult.reload.integrations.GeyserQueue.class);
        constructor.setAccessible(true);
        var old = (org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler)
                constructor.newInstance(session, upstream, null, new ac.cult.reload.integrations.GeyserQueue());
        var reply = new org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket();
        reply.setFromServer(true);
        old.handlePacket(reply);
        Mockito.verifyZeroInteractions(replacement);
        var detached = type.getDeclaredField("detached");
        detached.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicBoolean) detached.get(old)).set(true);
        var handedOff = type.getDeclaredField("handedOff");
        handedOff.setAccessible(true);
        handedOff.set(old, true);
        accepted.removeFirst().run();
        Mockito.verify(replacement).handlePacket(reply);
        assertTrue(accepted.isEmpty());
    }

    @Test
    public void unfinishedSpawnCannotReleaseWithoutTheGuaranteedDimensionReset() {
        var session = Mockito.mock(org.geysermc.geyser.session.GeyserSession.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(session.getTickEventLoop().inEventLoop()).thenReturn(true);
        assertNull(GeyserBedrockBridgeRuntime.releaseSession(session));
        Mockito.verify(session, Mockito.never()).getUpstream();
        Mockito.verify(session, Mockito.never()).disconnect(Mockito.anyString());
    }

    @Test
    public void transferFencesEngineReceiptsWithoutChangingNativeLatencyOrder() {
        var queue = new ac.cult.reload.integrations.GeyserQueue();
        var old = queue.scope();
        List<String> receipts = new ArrayList<>();
        queue.add(() -> receipts.add("native-A"));
        old.writeWithReceipt(() -> () -> receipts.add("old-receipt"));
        old.insert(() -> receipts.add("old-engine"), () -> {});
        queue.add(() -> receipts.add("native-B"));
        // Geyser may already have polled a callback to dispatch onto its tick executor.
        Runnable polled = queue.poll();
        old.close();
        var next = queue.scope();
        next.writeWithReceipt(() -> () -> receipts.add("new-receipt"));
        next.insert(() -> receipts.add("new-engine"), () -> {});
        polled.run();
        queue.poll().run();
        assertEquals(List.of("native-A"), receipts);
        queue.poll().run();
        queue.poll().run();
        assertEquals(List.of("native-A", "new-receipt", "native-B", "new-engine"), receipts);
        assertTrue(queue.isEmpty());
        assertTrue(queue.isTrackingWrites());
    }

    @Test
    public void detachedTeardownForwardsPendingLatencyBeforeAndAfterTrackingStops() throws Exception {
        try (var outbound = new OutboundFixture()) {
            List<String> completed = new ArrayList<>();
            outbound.queue.add(() -> completed.add("first"));
            outbound.queue.add(() -> completed.add("second"));
            outbound.detach(false);

            outbound.forwardLatency();
            assertNull(outbound.queue.poll());
            outbound.queue.stopTrackingWrites();
            outbound.forwardLatency();

            outbound.queue.poll().run();
            outbound.queue.poll().run();
            assertEquals(List.of("first", "second"), completed);
            assertTrue(outbound.queue.isEmpty());
        }
    }

    @Test
    public void transferredTapForwardsPendingLatencyAfterTheQueueIsRetired() throws Exception {
        try (var outbound = new OutboundFixture()) {
            Runnable callback = () -> {};
            outbound.queue.add(callback);
            outbound.detach(true);
            // The replacement tap can also detach before this old context drains.
            outbound.queue.stopTrackingWrites();

            outbound.forwardLatency();

            assertSame(callback, outbound.queue.poll());
            assertTrue(outbound.queue.isEmpty());
        }
    }

    @Test
    public void closedSessionForwardsPendingLatencyAfterItsQueueIsCleared() throws Exception {
        try (var outbound = new OutboundFixture()) {
            outbound.queue.add(() -> {});
            outbound.detach(true);
            Mockito.when(outbound.session.isClosed()).thenReturn(true);
            outbound.queue.clear();

            outbound.forwardLatency();

            assertTrue(outbound.queue.isEmpty());
        }
    }

    @Test
    public void transferredTapTracksPendingNativeLatencyAndFencesRetiredEngineReceipts() throws Exception {
        try (var outbound = new OutboundFixture()) {
            List<String> completed = new ArrayList<>();
            outbound.queue.add(() -> completed.add("native-A"));
            outbound.scope.writeWithReceipt(() -> () -> completed.add("retired-receipt"));
            outbound.scope.insert(() -> completed.add("retired-engine"), () -> {});
            outbound.queue.add(() -> completed.add("native-B"));
            outbound.detach(true);

            outbound.forwardLatency();
            outbound.queue.scope().insert(() -> completed.add("new-engine"), () -> {});
            while (!outbound.queue.isEmpty()) outbound.queue.poll().run();

            assertEquals(List.of("native-A", "native-B", "new-engine"), completed);
            assertTrue(outbound.queue.isTrackingWrites());
        }
    }

    @Test
    public void transferredTapStillRejectsLatencyWithoutANativeCallback() throws Exception {
        try (var outbound = new OutboundFixture()) {
            outbound.detach(true);
            var wrapper = latencyPacket();
            try {
                var failure = assertThrows(IllegalStateException.class, () -> outbound.wire.writeOutbound(wrapper));
                assertEquals("Latency write has no Geyser callback", failure.getMessage());
                assertNull(outbound.wire.readOutbound());
            } finally {
                wrapper.release();
            }
        }
    }

    private static BedrockPacketWrapper latencyPacket() {
        var packet = new NetworkStackLatencyPacket();
        packet.setFromServer(true);
        return BedrockPacketWrapper.create(0, 0, 0, packet, null);
    }

    private static final class OutboundFixture implements AutoCloseable {
        final GeyserSession session = Mockito.mock(GeyserSession.class);
        final GeyserQueue queue = new GeyserQueue();
        final GeyserQueue.Scope scope;
        final EmbeddedChannel wire;
        private final Class<?> tapType;
        private final Object tap;

        OutboundFixture() throws Exception {
            tapType = Class.forName(GeyserBedrockBridgeRuntime.class.getName() + "$PacketTapHandler");
            var constructor = tapType.getDeclaredConstructor(
                    GeyserSession.class,
                    org.cloudburstmc.protocol.bedrock.BedrockSession.class,
                    org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler.class,
                    GeyserQueue.class);
            constructor.setAccessible(true);
            tap = constructor.newInstance(session, null, null, queue);
            var scopeField = tapType.getDeclaredField("latencyScope");
            scopeField.setAccessible(true);
            scope = (GeyserQueue.Scope) scopeField.get(tap);
            var outboundType = Class.forName(GeyserBedrockBridgeRuntime.class.getName() + "$OutboundPacketTap");
            var outboundConstructor = outboundType.getDeclaredConstructor(tapType);
            outboundConstructor.setAccessible(true);
            wire = new EmbeddedChannel((ChannelHandler) outboundConstructor.newInstance(tap));
        }

        void detach(boolean transferred) throws Exception {
            var detached = tapType.getDeclaredField("detached");
            detached.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicBoolean) detached.get(tap)).set(true);
            var handedOff = tapType.getDeclaredField("handedOff");
            handedOff.setAccessible(true);
            handedOff.set(tap, transferred);
            scope.close();
        }

        void forwardLatency() {
            var wrapper = latencyPacket();
            try {
                assertTrue(wire.writeOutbound(wrapper));
                assertSame(wrapper, wire.readOutbound());
                assertNull(wire.readOutbound());
            } finally {
                wrapper.release();
            }
        }

        @Override
        public void close() {
            scope.close();
            wire.finishAndReleaseAll();
        }
    }

    @Test
    public void loginHandlerSwapHandsTheLatencyQueueToTheSuccessorTap() throws Exception {
        try (var login = new LoginSwapFixture()) {
            List<String> completed = new ArrayList<>();
            // SessionInitializeEvent: the first tap wraps Geyser's LoginPacketHandler.
            login.install();
            var first = login.handler;
            var queue = (GeyserQueue) login.session.getLatencyPingCache();
            queue.add(() -> completed.add("native-A"));
            login.writeLatency();
            login.field(first, "latencyScope", GeyserQueue.Scope.class)
                    .insert(() -> completed.add("retired-engine"), () -> {});
            // Cloudburst handed this reply to the first tap; it still awaits the tick loop.
            first.handlePacket(login.reply());

            // LoginPacketHandler#finishLogin installs UpstreamPacketHandler, then
            // SessionLoginEvent reaches Cult off the tick loop, which defers the old cleanup.
            login.handler = login.upstream;
            login.install();
            var successor = login.handler;
            assertTrue(successor != first && login.tapType.isInstance(successor));
            assertSame(queue, login.field(successor, "latencyQueue", GeyserQueue.class));
            login.tickLoop.drain();
            login.wire.runPendingTasks();
            successor.handlePacket(login.reply());
            login.tickLoop.drain();

            // Geyser registers the callback before Cloudburst writes the packet.
            queue.add(() -> completed.add("native-B"));
            login.writeLatency();
            successor.handlePacket(login.reply());
            login.tickLoop.drain();

            assertEquals(List.of("native-A", "native-B"), completed);
            assertTrue(queue.isEmpty());
            assertTrue(queue.isTrackingWrites());
            assertNull(login.wire.pipeline().get(login.field(first, "outboundHandlerName", String.class)));
            assertNotNull(login.wire.pipeline().get(login.field(successor, "outboundHandlerName", String.class)));
        }
    }

    /** Geyser's tick loop: tasks from other threads queue, tasks on the loop run inline. */
    private static final class TickLoop {
        private final Deque<Runnable> tasks = new ArrayDeque<>();
        private boolean running;

        void ensure(Runnable task) {
            if (running) task.run();
            else tasks.add(task);
        }

        void drain() {
            running = true;
            try {
                while (!tasks.isEmpty()) tasks.removeFirst().run();
            } finally {
                running = false;
            }
        }
    }

    private static final class LoginSwapFixture implements AutoCloseable {
        final GeyserSession session = Mockito.mock(GeyserSession.class);
        final org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler upstream =
                Mockito.mock(org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler.class);
        final EmbeddedChannel wire = new EmbeddedChannel();
        final TickLoop tickLoop = new TickLoop();
        final Class<?> tapType;
        org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler handler =
                Mockito.mock(org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler.class);

        LoginSwapFixture() throws Exception {
            tapType = Class.forName(GeyserBedrockBridgeRuntime.class.getName() + "$PacketTapHandler");
            var cache = GeyserSession.class.getDeclaredField("latencyPingCache");
            cache.setAccessible(true);
            cache.set(session, new java.util.concurrent.ConcurrentLinkedQueue<Runnable>());
            Mockito.doCallRealMethod().when(session).getLatencyPingCache();
            var geyserUpstream = Mockito.mock(org.geysermc.geyser.session.UpstreamSession.class);
            var bedrock = Mockito.mock(org.cloudburstmc.protocol.bedrock.BedrockServerSession.class);
            var peer = Mockito.mock(org.cloudburstmc.protocol.bedrock.BedrockPeer.class);
            Mockito.when(session.getUpstream()).thenReturn(geyserUpstream);
            Mockito.when(geyserUpstream.getSession()).thenReturn(bedrock);
            Mockito.when(bedrock.getPeer()).thenReturn(peer);
            Mockito.when(peer.getChannel()).thenReturn(wire);
            Mockito.when(bedrock.getPacketHandler()).thenAnswer(call -> handler);
            Mockito.doAnswer(call -> {
                        handler = call.getArgument(0);
                        return null;
                    })
                    .when(bedrock)
                    .setPacketHandler(Mockito.any());
            Mockito.when(session.getTickEventLoop()).thenReturn(wire.eventLoop());
            Mockito.doAnswer(call -> {
                        tickLoop.ensure(call.getArgument(0));
                        return null;
                    })
                    .when(session)
                    .ensureInEventLoop(Mockito.any(Runnable.class));
            // BedrockNetworkStackLatencyTranslator: each reply runs the next written callback.
            Mockito.doAnswer(call -> {
                        Runnable callback = session.getLatencyPingCache().poll();
                        if (callback != null) callback.run();
                        return org.cloudburstmc.protocol.common.PacketSignal.HANDLED;
                    })
                    .when(upstream)
                    .handlePacket(Mockito.any());
        }

        void install() throws Exception {
            var install = GeyserBedrockBridgeRuntime.class.getDeclaredMethod(
                    "installPacketTap", org.geysermc.geyser.api.connection.GeyserConnection.class);
            install.setAccessible(true);
            install.invoke(null, session);
        }

        <T> T field(Object tap, String name, Class<T> type) throws Exception {
            var field = tapType.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(tap));
        }

        NetworkStackLatencyPacket reply() {
            var reply = new NetworkStackLatencyPacket();
            reply.setFromServer(true);
            return reply;
        }

        void writeLatency() {
            var wrapper = latencyPacket();
            try {
                assertTrue(wire.writeOutbound(wrapper));
                assertSame(wrapper, wire.readOutbound());
                assertNull(wire.readOutbound());
            } finally {
                wrapper.release();
            }
        }

        @Override
        public void close() throws Exception {
            var taps = GeyserBedrockBridgeRuntime.class.getDeclaredField("PACKET_TAPS");
            taps.setAccessible(true);
            ((java.util.Map<?, ?>) taps.get(null)).remove(session);
            wire.finishAndReleaseAll();
        }
    }

    @Test
    public void continuationRetainsShiftedOriginsForPacketsAwaitingCloudburstWrite() {
        var previous = new BedrockOriginDispatch();
        var packet = new MovePlayerPacket();
        previous.begin();
        previous.enqueue(packet, () -> {});
        var operation = new ac.cult.cultac.bedrock.protocol.BedrockTeleportOperation(
                42, ac.cult.cultac.bedrock.protocol.BedrockTeleportProvenance.GFP_REBASE, null);
        previous.finish(16000000, -16000000, operation);
        var continued = new BedrockOriginDispatch();
        continued.restore(previous.continuation());
        previous.clear();
        assertEquals(
                new ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame(16000000, -16000000, 1), continued.frame());
        var emission = continued.take(packet);
        assertEquals(continued.frame(), emission.frame());
        assertEquals(operation, emission.operation());
        assertNull(continued.take(packet));
    }

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
        GeyserBedrockBridgeRuntime.correctCollisions(
                packet, ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags.AIR);
        assertFalse(packet.getInputData().contains(PlayerAuthInputData.VERTICAL_COLLISION));
        assertFalse(packet.getInputData().contains(PlayerAuthInputData.HORIZONTAL_COLLISION));
        assertTrue(packet.getInputData().contains(PlayerAuthInputData.PERFORM_ITEM_STACK_REQUEST));
        GeyserBedrockBridgeRuntime.correctCollisions(
                packet, ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags.ON_GROUND);
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
        Mockito.doAnswer(invocation -> {
                    packets.add(invocation.getArgument(0));
                    return null;
                })
                .when(session)
                .sendUpstreamPacket(Mockito.any());
        Deque<Runnable> eventLoop = new ArrayDeque<>();
        Mockito.doAnswer(invocation -> {
                    eventLoop.add(invocation.getArgument(0));
                    return null;
                })
                .when(session)
                .ensureInEventLoop(Mockito.any(Runnable.class));
        List<Integer> pongs = new ArrayList<>();
        Mockito.doAnswer(invocation -> {
                    var pong = (org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundPongPacket)
                            invocation.getArgument(0);
                    pongs.add(pong.getId());
                    return null;
                })
                .when(session)
                .sendDownstreamPacket(Mockito.any());

        var pingTranslator = new org.geysermc.geyser.translator.protocol.java.JavaPingTranslator();
        pingTranslator.translate(
                session, new org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundPingPacket(101));
        pingTranslator.translate(
                session, new org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundPingPacket(102));
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
        var queue = new ac.cult.reload.integrations.GeyserQueue();
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
        Mockito.doAnswer(invocation -> {
                    eventLoop.add(invocation.getArgument(0));
                    return null;
                })
                .when(session)
                .ensureInEventLoop(Mockito.any(Runnable.class));
        GeyserBedrockBridgeRuntime.nativeLatencyCallback(session, 123).run();
        Mockito.verify(session, Mockito.never()).sendDownstreamPacket(Mockito.any());
        eventLoop.removeFirst().run();
        Mockito.verify(session, Mockito.never()).sendDownstreamPacket(Mockito.any());
    }

    @Test
    public void failedWriteLeavesCallbackUnconfirmedAndDetachPreservesGeyserCallbacks() {
        var queue = new ac.cult.reload.integrations.GeyserQueue();
        Runnable callback = () -> {};
        queue.add(callback);
        org.junit.Assert.assertThrows(
                IllegalStateException.class,
                () -> queue.write(() -> {
                    throw new IllegalStateException("write failed");
                }));
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
        Mockito.when(otherUnsafe.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 32146));
        // Two players behind the same IP still have distinct native local peers.
        Mockito.when(matchingServer.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));
        Mockito.when(otherServer.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));

        assertTrue(GeyserBedrockBridgeRuntime.sameJavaConnection(geyserDownstream, matchingServer));
        assertFalse(GeyserBedrockBridgeRuntime.sameJavaConnection(geyserDownstream, otherServer));
        assertFalse(GeyserBedrockBridgeRuntime.sameJavaConnection(geyserDownstream, new Object()));
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
        assertTrue(GeyserBedrockBridgeRuntime.isGeyserPositionTeleport(MovePlayerPacket.Mode.RESPAWN));
        assertTrue(GeyserBedrockBridgeRuntime.isGeyserPositionTeleport(MovePlayerPacket.Mode.TELEPORT));
        assertFalse(GeyserBedrockBridgeRuntime.isGeyserPositionTeleport(MovePlayerPacket.Mode.NORMAL));
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

        ac.cult.cultac.utils.math.Vec3 physicalFeet = GeyserBedrockBridgeRuntime.toJavaPosition(packetPosition);

        assertEquals((double) packetPosition.getX(), physicalFeet.x, 0.0D);
        assertEquals((double) packetPosition.getY() - 1.6200103759765625D, physicalFeet.y, 0.0D);
        assertEquals((double) packetPosition.getZ(), physicalFeet.z, 0.0D);
    }
}
