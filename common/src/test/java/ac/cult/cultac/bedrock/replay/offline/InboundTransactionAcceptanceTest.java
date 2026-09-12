package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.cultac.events.packets.listeners.PacketPingListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Method;
import java.util.UUID;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import net.minecraft.world.phys.Vec3;
import ac.cult.cultac.checks.impl.prediction.runner.PacketModHandler;
import static org.junit.Assert.assertTrue;

public final class InboundTransactionAcceptanceTest {
    @Test
    public void legacyTransactionsSurviveInventoryAcknowledgementsWithoutPrematureConfirmation() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        // Protocol 754 is the final pre-ping release; protocol 47 covers the 1.8 path.
        for (int protocol : new int[]{47, 754}) {
            CultPlayer player = offlineJavaPlayer(ClientVersion.fromProtocolVersion(protocol));
            try {
                var transactions = new java.util.ArrayList<CultPlayer.TrackedTransaction>();
                var ids = new java.util.HashSet<Integer>();
                var completed = new java.util.ArrayList<Integer>();
                for (int i = 0; i < 512; i++) {
                    var transaction = createTrackedTransaction(player);
                    transactions.add(transaction);
                    // ViaBackwards requires this exact equality to send CONTAINER_ACK.
                    assertEquals(transaction.id(), (int) (short) transaction.id());
                    assertTrue("Pending and sent transactions must have distinct ids", ids.add(transaction.id()));
                    player.latencyUtils.addRealTimeTask(transaction.transaction(),
                            () -> completed.add(transaction.transaction()));
                    // Leave alternating packets pending to cover deferred/bundled allocation too.
                    if ((i & 1) == 0) {
                        player.markTrackedTransactionPacketSent(transaction);
                    }
                }
                assertEquals(0, player.lastTransactionReceived.get());
                assertTrue(completed.isEmpty());

                for (var transaction : transactions) {
                    player.markTrackedTransactionPacketSent(transaction);
                }
                // The final odd entry was written last. Its signed-short echo must
                // release all preceding compensation tasks, once, in order.
                var last = transactions.getLast();
                assertTrue(player.addTransactionResponse((short) last.id()));
                assertEquals(last.transaction(), player.lastTransactionReceived.get());
                assertEquals(transactions.stream().map(CultPlayer.TrackedTransaction::transaction).toList(), completed);
                assertFalse(player.addTransactionResponse((short) last.id()));
                assertEquals(transactions.size(), completed.size());
            } finally {
                OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
            }
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
            PacketReceiveEvent accepted = receiveEvent(player, new ServerboundPongPacket(transaction.id()));
            listener.onPong(accepted, player, (ServerboundPongPacket) accepted.getNmsPacket());
            assertTrue(accepted.isAcceptedTransactionResponse());

            PacketReceiveEvent unknown = receiveEvent(player, new ServerboundPongPacket(transaction.id()));
            listener.onPong(unknown, player, (ServerboundPongPacket) unknown.getNmsPacket());
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
            org.junit.Assert.assertNotNull(pending);
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
        var replies = new ac.cult.cultac.utils.latency.GeyserQueue();
        try {
            var first = createTrackedTransaction(player);
            player.markTrackedTransactionPacketSent(first);
            replies.add(() -> player.addTransactionResponse(first.id()));
            replies.write(() -> player.markBedrockTransactionClientbound(first.id()));
            reply(replies, 0);
            var future = createTrackedTransaction(player);
            player.markTrackedTransactionPacketSent(future);
            replies.add(() -> player.addTransactionResponse(future.id()));
            var completed = new java.util.ArrayList<String>();
            player.latencyUtils.addRealTimeTask(future.transaction(), () -> completed.add("future"));

            var a = player.createBedrockTransactionAfterClientbound();
            replies.insert(() -> player.addTransactionResponse(a.id()),
                    () -> player.markBedrockTransactionClientbound(a.id()));
            var b = player.createBedrockTransactionAfterClientbound();
            replies.insert(() -> player.addTransactionResponse(b.id()),
                    () -> player.markBedrockTransactionClientbound(b.id()));
            player.addBedrockTransactionTask(b, () -> completed.add("B"));
            player.addBedrockTransactionTask(a, () -> completed.add("A"));
            reply(replies, Long.MIN_VALUE);
            org.junit.Assert.assertEquals(java.util.List.of("A"), completed);
            org.junit.Assert.assertEquals(first.transaction(), player.lastTransactionReceived.get());
            org.junit.Assert.assertEquals(future.transaction(), player.lastTransactionSent.get());
            reply(replies, Long.MIN_VALUE);
            org.junit.Assert.assertEquals(java.util.List.of("A", "B"), completed);
            // Unwritten Java callbacks cannot be consumed, regardless of the returned timestamp.
            reply(replies, future.id());
            org.junit.Assert.assertEquals(java.util.List.of("A", "B"), completed);
            replies.write(() -> player.markBedrockTransactionClientbound(future.id()));
            reply(replies, 1);
            org.junit.Assert.assertEquals(java.util.List.of("A", "B", "future"), completed);
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
        var replies = new ac.cult.cultac.utils.latency.GeyserQueue();
        try {
            var marker = player.createBedrockTransactionAfterClientbound();
            var completed = new java.util.ArrayList<String>();
            player.addBedrockTransactionTask(marker, () -> completed.add("received"));
            long clock = player.getPlayerClockAtLeast();
            reply(replies, marker.id());
            assertTrue(completed.isEmpty());
            org.junit.Assert.assertEquals(clock, player.getPlayerClockAtLeast());
            replies.insert(() -> player.addTransactionResponse(marker.id()), () -> {
                reply(replies, marker.id());
                assertTrue(completed.isEmpty());
                player.markBedrockTransactionClientbound(marker.id());
            });
            reply(replies, 0);
            org.junit.Assert.assertEquals(java.util.List.of("received"), completed);
            reply(replies, 0);
            org.junit.Assert.assertEquals(java.util.List.of("received"), completed);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static void reply(ac.cult.cultac.utils.latency.GeyserQueue replies, long timestamp) {
        var session = org.mockito.Mockito.mock(org.geysermc.geyser.session.GeyserSession.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
        org.mockito.Mockito.when(session.getLatencyPingCache()).thenReturn(replies);
        var packet = new org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket();
        packet.setTimestamp(timestamp);
        new org.geysermc.geyser.translator.protocol.bedrock.BedrockNetworkStackLatencyTranslator().translate(session, packet);
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
            var completed = new java.util.ArrayList<String>();
            player.latencyUtils.addRealTimeTaskWithNextTransaction(first.transaction(),
                    () -> completed.add("first"), () -> completed.add("confirmed"));
            player.latencyUtils.addRealTimeTask(later.transaction(), () -> completed.add("later"));
            player.addBedrockTransactionTask(inserted, () -> completed.add("inserted"));
            assertTrue(player.addTransactionResponse(later.id()));
            org.junit.Assert.assertEquals(java.util.List.of("first", "inserted", "confirmed", "later"), completed);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void outOfOrderTasksAndDeferredContinuationsKeepStableOrderAcrossDrains() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            var markers = new java.util.ArrayList<CultPlayer.BedrockTransaction>();
            var actual = new java.util.ArrayList<String>();
            var expected = new java.util.ArrayList<java.util.List<String>>();
            for (int i = 0; i < 8; i++) {
                var ping = createTrackedTransaction(player);
                player.markTrackedTransactionPacketSent(ping);
                player.markBedrockTransactionClientbound(ping.id());
                int index = i;
                player.latencyUtils.addRealTimeTaskWithNextTransaction(ping.transaction(),
                        () -> actual.add("java-" + index), () -> actual.add("next-" + index));
                var marker = player.createBedrockTransactionAfterClientbound();
                player.markBedrockTransactionClientbound(marker.id());
                markers.add(marker);
                expected.add(new java.util.ArrayList<>());
            }
            var random = new java.util.Random(42);
            for (int i = 0; i < 256; i++) {
                int index = random.nextInt(markers.size());
                String label = Integer.toString(i);
                player.addBedrockTransactionTask(markers.get(index), () -> actual.add(label));
                expected.get(index).add(label);
            }
            var completed = new java.util.ArrayList<String>();
            for (int i = 0; i < markers.size(); i++) {
                var marker = markers.get(i);
                assertTrue(player.addTransactionResponse(marker.id()));
                if (i > 0) completed.add("next-" + (i - 1));
                completed.add("java-" + i);
                completed.addAll(expected.get(i));
                org.junit.Assert.assertEquals(completed, actual);
                assertFalse(player.addTransactionResponse(marker.id()));
                org.junit.Assert.assertEquals(completed, actual);
            }
            // Registering after acknowledgement runs once immediately.
            player.addBedrockTransactionTask(markers.get(0), () -> actual.add("late"));
            completed.add("late");
            org.junit.Assert.assertEquals(completed, actual);
            // The final continuation still requires the next real Java ping.
            var last = createTrackedTransaction(player);
            player.markTrackedTransactionPacketSent(last);
            player.markBedrockTransactionClientbound(last.id());
            assertTrue(player.addTransactionResponse(last.id()));
            completed.add("next-7");
            org.junit.Assert.assertEquals(completed, actual);
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
        Method method = CultPlayer.class.getDeclaredMethod("createTrackedTransaction");
        method.setAccessible(true);
        return (CultPlayer.TrackedTransaction) method.invoke(player);
    }

    private static PacketReceiveEvent receiveEvent(CultPlayer player, ServerboundPongPacket packet) {
        return new PacketReceiveEvent(player.user, packet, ConnectionProtocol.PLAY);
    }

    private static CultPlayer offlineJavaPlayer() {
        return offlineJavaPlayer(ClientVersion.fromProtocolVersion(net.minecraft.SharedConstants.getProtocolVersion()));
    }

    private static CultPlayer offlineJavaPlayer(ClientVersion version) {
        UUID playerId = UUID.fromString("9c5e440b-265d-435f-98f1-1f539659c003");
        User user = new User(
                new User.Profile(playerId, ".Transaction_Test"),
                null,
                null,
                null,
                new EmbeddedChannel());
        return new CultPlayer(user) {
            @Override
            public ClientVersion getClientVersion() {
                return version;
            }
        };
    }
}
