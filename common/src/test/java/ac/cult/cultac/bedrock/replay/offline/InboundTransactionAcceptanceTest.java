package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.bedrock.bridge.GeyserBedrockBridgeRuntime;
import ac.cult.cultac.checks.impl.movement.timer.AbstractTimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.TimerCheck;
import ac.cult.cultac.checks.impl.prediction.runner.PacketModHandler;
import ac.cult.cultac.events.packets.listeners.PacketPingListener;
import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.CultWrite;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.reload.integrations.GeyserQueue;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.protocol.bedrock.BedrockNetworkStackLatencyTranslator;
import org.geysermc.geyser.translator.protocol.java.JavaPingTranslator;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundPingPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundPongPacket;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public final class InboundTransactionAcceptanceTest {
    @Test
    public void transactionTrackingUsesTheWirePingIdAndMarksItOnlyOnce() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = offlineJavaPlayer();
        try {
            var first = createTrackedTransaction(player);
            var second = createTrackedTransaction(player);
            var completed = new ArrayList<Integer>();
            player.latencyUtils.addRealTimeTask(first.transaction(), () -> completed.add(first.transaction()));
            player.latencyUtils.addRealTimeTask(second.transaction(), () -> completed.add(second.transaction()));
            // A decoded or re-encoded ping has the same wire ID and a different Java identity.
            var decoded = new CultWrite(new ClientboundPing(first.id()), false);
            assertTrue(player.markTransactionPacketSent(decoded));
            assertFalse(player.markTransactionPacketSent(first.packet()));
            assertFalse(player.markTransactionPacketSent(first.id(), 0));
            assertTrue(player.markTransactionPacketSent(second.id(), 0));
            assertTrue(player.addTransactionResponse(second.id()));
            assertEquals(List.of(first.transaction(), second.transaction()), completed);
            assertFalse(player.addTransactionResponse(second.id()));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void acceptedResponseFactIsScopedToTheCurrentReceiveEvent() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = offlineJavaPlayer();
        try {
            CultPlayer.TrackedTransaction transaction = createTrackedTransaction(player);
            player.markTrackedTransactionPacketSent(transaction);

            PacketPingListener listener = new PacketPingListener();
            var accepted = RecordReceiveTestEvents.pong(player, transaction.id());
            listener.onPong(accepted, player, accepted.getPacket());
            assertTrue(accepted.isAcceptedTransactionResponse());

            var unknown = RecordReceiveTestEvents.pong(player, transaction.id());
            listener.onPong(unknown, player, unknown.getPacket());
            assertFalse(unknown.isAcceptedTransactionResponse());

            // The accepted fact remains attached to its original event; processing the
            // next pong cannot mutate what downstream consumers saw for the first one.
            assertTrue(accepted.isAcceptedTransactionResponse());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void velocitySandwichUsesTheExistingTransactionQueue() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = offlineJavaPlayer();
        try {
            var before = createTrackedTransaction(player);
            player.markTrackedTransactionPacketSent(before);
            registerVelocity(player);
            var after = createTrackedTransaction(player);
            player.markTrackedTransactionPacketSent(after);
            var kb = player.checkManager.getKnockbackHandler();
            assertNull(kb.firstBread);
            assertNull(kb.secondBread);
            assertTrue(player.addTransactionResponse(before.id()));
            var pending = kb.firstBread;
            Assert.assertNotNull(pending);
            assertNull(kb.secondBread);
            assertFalse(player.addTransactionResponse(before.id()));
            assertNull(kb.secondBread);
            assertTrue(player.addTransactionResponse(after.id()));
            assertSame(pending, kb.secondBread);
            assertNull(kb.firstBread);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void receiptCannotRestoreDiscardedVelocity() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = offlineJavaPlayer();
        try {
            var before = createTrackedTransaction(player);
            player.markTrackedTransactionPacketSent(before);
            registerVelocity(player);
            var after = createTrackedTransaction(player);
            player.markTrackedTransactionPacketSent(after);
            assertTrue(player.addTransactionResponse(before.id()));
            var kb = player.checkManager.getKnockbackHandler();
            kb.clearVelocitiesFromSourceEntity(player.entityID);
            assertTrue(player.addTransactionResponse(after.id()));
            assertNull(kb.firstBread);
            assertNull(kb.secondBread);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void nativeBoundariesCanPrecedeAnAlreadyAllocatedJavaPing() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        var replies = new GeyserQueue();
        try {
            var first = createTrackedTransaction(player);
            player.markTrackedTransactionPacketSent(first);
            replies.add(() -> player.addTransactionResponse(first.id()));
            replies.write(() -> player.markBedrockTransactionClientbound(first.id()));
            reply(replies, 0);
            var future = createTrackedTransaction(player);
            player.markTrackedTransactionPacketSent(future);
            replies.add(() -> player.addTransactionResponse(future.id()));
            var completed = new ArrayList<String>();
            player.latencyUtils.addRealTimeTask(future.transaction(), () -> completed.add("future"));

            var a = player.createBedrockTransactionAfterClientbound();
            replies.insert(
                    () -> player.addTransactionResponse(a.id()),
                    () -> player.markBedrockTransactionClientbound(a.id()));
            var b = player.createBedrockTransactionAfterClientbound();
            replies.insert(
                    () -> player.addTransactionResponse(b.id()),
                    () -> player.markBedrockTransactionClientbound(b.id()));
            player.addBedrockTransactionTask(b, () -> completed.add("B"));
            player.addBedrockTransactionTask(a, () -> completed.add("A"));
            reply(replies, Long.MIN_VALUE);
            Assert.assertEquals(List.of("A"), completed);
            Assert.assertEquals(first.transaction(), player.lastTransactionReceived.get());
            Assert.assertEquals(future.transaction(), player.lastTransactionSent.get());
            reply(replies, Long.MIN_VALUE);
            Assert.assertEquals(List.of("A", "B"), completed);
            // Unwritten Java callbacks cannot be consumed, regardless of the returned timestamp.
            reply(replies, future.id());
            Assert.assertEquals(List.of("A", "B"), completed);
            replies.write(() -> player.markBedrockTransactionClientbound(future.id()));
            reply(replies, 1);
            Assert.assertEquals(List.of("A", "B", "future"), completed);
            assertFalse(player.addTransactionResponse(a.id()));
            assertTrue(replies.isEmpty());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void nativeReplyBeforeWriteCannotConfirmAnything() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        var replies = new GeyserQueue();
        try {
            var marker = player.createBedrockTransactionAfterClientbound();
            var completed = new ArrayList<String>();
            player.addBedrockTransactionTask(marker, () -> completed.add("received"));
            long clock = player.getPlayerClockAtLeast();
            reply(replies, marker.id());
            assertTrue(completed.isEmpty());
            Assert.assertEquals(clock, player.getPlayerClockAtLeast());
            replies.insert(() -> player.addTransactionResponse(marker.id()), () -> {
                reply(replies, marker.id());
                assertTrue(completed.isEmpty());
                player.markBedrockTransactionClientbound(marker.id());
            });
            reply(replies, 0);
            Assert.assertEquals(List.of("received"), completed);
            reply(replies, 0);
            Assert.assertEquals(List.of("received"), completed);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void javaDerivedNativeReceiptCompensatesBeforeForwardingItsDeferredPong() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            var completed = new ArrayList<String>();
            var ping = new DeferredJavaPing(player, () -> {
                assertEquals(List.of("chunk", "attributes", "metadata"), completed);
                completed.add("forward-pong");
            });
            player.latencyUtils.addRealTimeTask(ping.transaction.transaction(), () -> completed.add("chunk"));
            player.latencyUtils.addRealTimeTask(ping.transaction.transaction(), () -> completed.add("attributes"));
            // Merely queued callbacks and even the correct echoed timestamp cannot confirm state.
            ping.reply(ping.packet.getTimestamp());
            assertEquals(0, player.lastTransactionReceived.get());
            assertTrue(completed.isEmpty());
            assertTrue(ping.pongs.isEmpty());

            ping.write(() -> assertTrue(PacketPingListener.acceptBedrockResponse(player, ping.transaction.id())));
            player.addBedrockTransactionTask(
                    player.getLastClientboundBedrockTransaction(), () -> completed.add("metadata"));
            // Deployed Geyser matches the actual written callback FIFO, not this returned timestamp.
            ping.reply(Long.MIN_VALUE);
            assertEquals(ping.transaction.transaction(), player.lastTransactionReceived.get());
            assertEquals(List.of("chunk", "attributes", "metadata", "forward-pong"), completed);
            assertEquals(1, ping.pongs.size());
            assertEquals(ping.transaction.id(), ping.pongs.getFirst().getId());

            long clock = player.getPlayerClockAtLeast();
            player.checkManager.getCheck(TimerCheck.class).onBedrockAuthInput();
            var late = RecordReceiveTestEvents.pong(
                    player, ping.pongs.removeFirst().getId());
            dispatchLocalBridgePong(player, late);
            assertFalse(late.isAcceptedTransactionResponse());
            assertEquals(clock, player.getPlayerClockAtLeast());
            assertEquals(ping.transaction.transaction(), player.lastTransactionReceived.get());
            ping.reply(ping.transaction.id());
            assertTrue(ping.pongs.isEmpty());
            assertEquals(4, completed.size());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void failedOrReentrantJavaMarkerWriteCannotRunItsLocalReceipt() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            var ping = new DeferredJavaPing(player, () -> {});
            var receipts = new AtomicInteger();
            Assert.assertThrows(
                    IllegalStateException.class,
                    () -> ping.replies.writeWithReceipt(() -> {
                        ping.reply(ping.transaction.id());
                        assertEquals(0, receipts.get());
                        assertTrue(ping.pongs.isEmpty());
                        throw new IllegalStateException("write failed");
                    }));
            ping.reply(ping.transaction.id());
            assertEquals(0, receipts.get());
            assertEquals(0, player.lastTransactionReceived.get());
            assertTrue(ping.pongs.isEmpty());

            ping.write(() -> {
                receipts.incrementAndGet();
                assertTrue(PacketPingListener.acceptBedrockResponse(player, ping.transaction.id()));
            });
            ping.reply(0);
            assertEquals(1, receipts.get());
            assertEquals(1, ping.pongs.size());
            ping.reply(0);
            assertEquals(1, receipts.get());
            assertEquals(1, ping.pongs.size());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void untrackedAndAlreadyAcknowledgedJavaMarkersKeepTheirOriginalCallbacks() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            var ping = new DeferredJavaPing(player, () -> {});
            // An ordinary untracked Geyser callback has no local Cult receipt.
            ping.replies.clear();
            int unknown = ping.transaction.id() == 17 ? 18 : 17;
            ping.translate(unknown);
            assertNull(ping.writtenReceipt(unknown));
            ping.replies.write(() -> {});
            ping.reply(unknown);
            assertEquals(0, player.lastTransactionReceived.get());
            assertEquals(1, ping.pongs.size());
            assertEquals(unknown, ping.pongs.getFirst().getId());
            player.markBedrockTransactionClientbound(ping.transaction.id());
            Assert.assertNotNull(ping.writtenReceipt(ping.transaction.id()));
            assertTrue(PacketPingListener.acceptBedrockResponse(player, ping.transaction.id()));
            long clock = player.getPlayerClockAtLeast();
            assertNull(ping.writtenReceipt(ping.transaction.id()));
            ping.translate(ping.transaction.id());
            ping.replies.writeWithReceipt(() -> {
                assertEquals(ping.transaction.transaction(), player.lastTransactionReceived.get());
                return null;
            });
            ping.reply(0);
            assertEquals(2, ping.pongs.size());
            assertEquals(clock, player.getPlayerClockAtLeast());
            assertFalse(PacketPingListener.acceptBedrockResponse(player, ping.transaction.id()));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void realGeyserJavaMarkersPreserveSignedPingIdsWithoutScaling() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            var ping = new DeferredJavaPing(player, () -> {});
            ping.replies.clear();
            for (int id : new int[] {Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE}) {
                ping.translate(id);
                assertTrue(ping.packet.isFromServer());
                assertEquals((long) id, ping.packet.getTimestamp());
                ping.replies.write(() -> {});
                ping.reply(123);
                assertEquals(id, ping.pongs.removeFirst().getId());
            }
            assertEquals(0, player.lastTransactionReceived.get());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void localReceiptFailureStillRunsTheOriginalWrittenCallbackOnce() {
        var replies = new GeyserQueue();
        var forwarded = new AtomicInteger();
        replies.add(forwarded::incrementAndGet);
        replies.writeWithReceipt(() -> () -> {
            throw new IllegalStateException("receipt failed");
        });
        Assert.assertThrows(IllegalStateException.class, () -> replies.poll().run());
        assertEquals(1, forwarded.get());
        assertNull(replies.poll());
    }

    static void dispatchLocalBridgePong(CultPlayer player, PacketReceiveEvent<ServerboundPong> event) throws Exception {
        var connection = player.user.getCultConnection();
        var dispatcher = connection.dispatcher();
        var timer = player.checkManager.getCheck(TimerCheck.class);
        var reached = new AtomicInteger();
        dispatcher.register(routes -> {
            routes.receive(ServerboundPackets.PONG, PacketListenerPriority.LOWEST, new PacketPingListener()::onPong);
            routes.receive(ServerboundPackets.PONG, PacketListenerPriority.LOW, timer::onPong);
            routes.receive(
                    ServerboundPackets.PONG,
                    PacketListenerPriority.MONITOR,
                    (received, owner, packet) -> reached.incrementAndGet());
        });
        var previousPlayer = connection.player();
        synchronized (connection) {
            connection.player(player);
        }
        var bridge = CultConnection.class.getDeclaredMethod("bedrockBridge", Object.class);
        bridge.setAccessible(true);
        Object previousBridge = connection.bedrockBridge();
        bridge.invoke(connection, new Object());
        try {
            var before = timerReceiptState(timer);
            assertEquals(Boolean.TRUE, before.getFirst());
            dispatcher.receive(event, dispatcher.get(ServerboundPackets.PONG).receive());
            assertEquals(0, reached.get());
            assertFalse(event.isAcceptedTransactionResponse());
            assertEquals(before, timerReceiptState(timer));
        } finally {
            bridge.invoke(connection, previousBridge);
            synchronized (connection) {
                connection.player(previousPlayer);
            }
        }
    }

    private static List<Object> timerReceiptState(TimerCheck timer) throws Exception {
        var values = new ArrayList<Object>();
        for (String name :
                List.of("hasGottenMovementAfterTransaction", "knownPlayerClockTime", "lastMovementPlayerClock")) {
            var field = AbstractTimerCheck.class.getDeclaredField(name);
            field.setAccessible(true);
            values.add(field.get(timer));
        }
        return values;
    }

    /** Real Geyser translators and Session callback registration, with Java Pong delivery deferred. */
    static final class DeferredJavaPing {
        final CultPlayer player;
        final CultPlayer.TrackedTransaction transaction;
        final GeyserQueue replies = new GeyserQueue();
        final Deque<ServerboundPongPacket> pongs = new ArrayDeque<>();
        final GeyserSession session;
        NetworkStackLatencyPacket packet;

        DeferredJavaPing(CultPlayer player, Runnable beforeForward) throws Exception {
            this.player = player;
            transaction = createTrackedTransaction(player);
            player.markTrackedTransactionPacketSent(transaction);
            session = Mockito.mock(GeyserSession.class, Mockito.CALLS_REAL_METHODS);
            var cache = GeyserSession.class.getDeclaredField("latencyPingCache");
            cache.setAccessible(true);
            cache.set(session, replies);
            var geyser = Mockito.mock(GeyserImpl.class, Mockito.RETURNS_DEEP_STUBS);
            Mockito.doReturn(geyser).when(session).getGeyser();
            Mockito.when(geyser.config().gameplay().forwardPlayerPing()).thenReturn(true);
            Mockito.doAnswer(invocation -> {
                        packet = invocation.getArgument(0);
                        return null;
                    })
                    .when(session)
                    .sendUpstreamPacket(Mockito.any());
            Mockito.doAnswer(invocation -> {
                        invocation.<Runnable>getArgument(0).run();
                        return null;
                    })
                    .when(session)
                    .ensureInEventLoop(Mockito.any(Runnable.class));
            Mockito.doAnswer(invocation -> {
                        beforeForward.run();
                        pongs.add(invocation.getArgument(0));
                        return null;
                    })
                    .when(session)
                    .sendDownstreamPacket(Mockito.any());
            translate(transaction.id());
            assertTrue(packet.isFromServer());
            assertEquals((long) transaction.id(), packet.getTimestamp());
            assertTrue(pongs.isEmpty());
        }

        void translate(int id) {
            new JavaPingTranslator().translate(session, new ClientboundPingPacket(id));
            assertEquals((long) id, packet.getTimestamp());
        }

        void write(Runnable receipt) {
            replies.writeWithReceipt(() -> {
                player.markBedrockTransactionClientbound(packet.getTimestamp());
                assertEquals(packet.getTimestamp(), (long)
                        player.getLastClientboundBedrockTransaction().id());
                Assert.assertNotNull(writtenReceipt(packet.getTimestamp()));
                return receipt;
            });
        }

        Runnable writtenReceipt(long timestamp) {
            try {
                var method = GeyserBedrockBridgeRuntime.class.getDeclaredMethod(
                        "writtenJavaLatencyCallback", GeyserSession.class, CultPlayer.class, long.class);
                method.setAccessible(true);
                return (Runnable) method.invoke(null, session, player, timestamp);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }

        void reply(long timestamp) {
            var response = new NetworkStackLatencyPacket();
            response.setTimestamp(timestamp);
            new BedrockNetworkStackLatencyTranslator().translate(session, response);
        }
    }

    private static void reply(GeyserQueue replies, long timestamp) {
        var session = Mockito.mock(GeyserSession.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(session.getLatencyPingCache()).thenReturn(replies);
        var packet = new NetworkStackLatencyPacket();
        packet.setTimestamp(timestamp);
        new BedrockNetworkStackLatencyTranslator().translate(session, packet);
    }

    @Test
    public void skippedBoundariesDrainTasksAndContinuationsInOrder() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            var first = createTrackedTransaction(player);
            player.markTrackedTransactionPacketSent(first);
            player.markBedrockTransactionClientbound(first.id());
            var inserted = player.createBedrockTransactionAfterClientbound();
            player.markBedrockTransactionClientbound(inserted.id());
            var later = createTrackedTransaction(player);
            player.markTrackedTransactionPacketSent(later);
            player.markBedrockTransactionClientbound(later.id());
            var completed = new ArrayList<String>();
            player.latencyUtils.addRealTimeTaskWithNextTransaction(
                    first.transaction(), () -> completed.add("first"), () -> completed.add("confirmed"));
            player.latencyUtils.addRealTimeTask(later.transaction(), () -> completed.add("later"));
            player.addBedrockTransactionTask(inserted, () -> completed.add("inserted"));
            assertTrue(player.addTransactionResponse(later.id()));
            Assert.assertEquals(List.of("first", "inserted", "confirmed", "later"), completed);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void outOfOrderTasksAndDeferredContinuationsKeepStableOrderAcrossDrains() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            var markers = new ArrayList<CultPlayer.BedrockTransaction>();
            var actual = new ArrayList<String>();
            var expected = new ArrayList<List<String>>();
            for (int i = 0; i < 8; i++) {
                var ping = createTrackedTransaction(player);
                player.markTrackedTransactionPacketSent(ping);
                player.markBedrockTransactionClientbound(ping.id());
                int index = i;
                player.latencyUtils.addRealTimeTaskWithNextTransaction(
                        ping.transaction(), () -> actual.add("java-" + index), () -> actual.add("next-" + index));
                var marker = player.createBedrockTransactionAfterClientbound();
                player.markBedrockTransactionClientbound(marker.id());
                markers.add(marker);
                expected.add(new ArrayList<>());
            }
            var random = new Random(42);
            for (int i = 0; i < 256; i++) {
                int index = random.nextInt(markers.size());
                String label = Integer.toString(i);
                player.addBedrockTransactionTask(markers.get(index), () -> actual.add(label));
                expected.get(index).add(label);
            }
            var completed = new ArrayList<String>();
            for (int i = 0; i < markers.size(); i++) {
                var marker = markers.get(i);
                assertTrue(player.addTransactionResponse(marker.id()));
                if (i > 0) completed.add("next-" + (i - 1));
                completed.add("java-" + i);
                completed.addAll(expected.get(i));
                Assert.assertEquals(completed, actual);
                assertFalse(player.addTransactionResponse(marker.id()));
                Assert.assertEquals(completed, actual);
            }
            // Registering after acknowledgement runs once immediately.
            player.addBedrockTransactionTask(markers.get(0), () -> actual.add("late"));
            completed.add("late");
            Assert.assertEquals(completed, actual);
            // The final continuation still requires the next real Java ping.
            var last = createTrackedTransaction(player);
            player.markTrackedTransactionPacketSent(last);
            player.markBedrockTransactionClientbound(last.id());
            assertTrue(player.addTransactionResponse(last.id()));
            completed.add("next-7");
            Assert.assertEquals(completed, actual);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static void registerVelocity(CultPlayer player) throws Exception {
        Method method = PacketModHandler.class.getDeclaredMethod(
                "registerTransactionSandwich", Vec3.class, boolean.class, int.class);
        method.setAccessible(true);
        method.invoke(player.checkManager.getKnockbackHandler(), new Vec3(0.125, 0.25, 0), true, player.entityID);
    }

    private static CultPlayer.TrackedTransaction createTrackedTransaction(CultPlayer player) throws Exception {
        Method method = CultPlayer.class.getDeclaredMethod("createTrackedTransaction", int.class, int.class);
        method.setAccessible(true);
        return (CultPlayer.TrackedTransaction) method.invoke(player, CultPlayer.PING_LEADING, 0);
    }

    private static PacketReceiveEvent receiveEvent(CultPlayer player, ServerboundPong packet) {
        return RecordReceiveTestEvents.pong(player, packet.id());
    }

    private static CultPlayer offlineJavaPlayer() {
        return offlineJavaPlayer(ClientVersion.fromProtocolVersion(ProtocolVersion.V26_3.protocol()));
    }

    private static CultPlayer offlineJavaPlayer(ClientVersion version) {
        UUID playerId = UUID.fromString("9c5e440b-265d-435f-98f1-1f539659c003");
        User user = TestUsers.create(new User.Profile(playerId, ".Transaction_Test"), new EmbeddedChannel());
        return new CultPlayer(user) {
            @Override
            public ClientVersion getClientVersion() {
                return version;
            }
        };
    }
}
