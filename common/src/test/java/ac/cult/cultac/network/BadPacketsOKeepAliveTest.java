package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.checks.impl.badpackets.BadPacketsO;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundKeepAlive;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundKeepAlive;
import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BadPacketsOKeepAliveTest {
    @Test
    void newerImmediateReplyCanPrecedeAnOlderDeferredReply() throws Exception {
        try (var f = new Fixture()) {
            // Captured 1.21.11 IDs in a source-permitted ordering: A deferred, B immediate before tick.
            f.send(20667176628L);
            int proof = f.send(20667177628L);
            f.reply(20667177628L);
            f.prove(proof);
            f.reply(20667176628L);
            f.tickEnd();
            assertEquals(0, f.check.getViolations());
            assertEquals(0, f.pending());
        }
    }

    @Test
    void unknownAndDuplicateRepliesStillFlag() throws Exception {
        try (var f = new Fixture()) {
            f.send(10);
            f.reply(10);
            f.reply(10);
            assertEquals(1, f.check.getViolations());
            f.reply(11);
            assertEquals(2, f.check.getViolations());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidRepliesKeepTheExistingPacketModificationPolicy(boolean observeOnly) throws Exception {
        try (var f = new Fixture();
                var mode = RecordReceiveFixture.observeOnly(observeOnly)) {
            f.check.setEnabled(true);
            assertEquals(!observeOnly, f.reply(42).isCancelled());
            assertEquals(1, f.check.getViolations());
        }
    }

    @Test
    void eachRepeatedServerChallengeAllowsOneReply() throws Exception {
        try (var f = new Fixture()) {
            f.send(Long.MIN_VALUE);
            f.send(Long.MIN_VALUE);
            f.reply(Long.MIN_VALUE);
            f.reply(Long.MIN_VALUE);
            assertEquals(0, f.check.getViolations());
            f.reply(Long.MIN_VALUE);
            assertEquals(1, f.check.getViolations());
        }
    }

    @Test
    void cleanupNeedsBothTheTrailingPongAndAFollowingTickEnd() throws Exception {
        try (var f = new Fixture()) {
            int firstProof = f.send(1);
            int secondProof = f.send(2);
            f.tickEnd();
            assertEquals(2, f.pending(), "a tick without receipt proof must preserve both IDs");
            f.prove(firstProof);
            assertEquals(2, f.pending(), "pong processing precedes the deferred-packet flush");
            f.tickEnd();
            assertEquals(1, f.pending(), "the later, unproven challenge must survive");
            f.reply(2);
            assertEquals(0, f.check.getViolations());
            f.reply(1);
            assertEquals(1, f.check.getViolations(), "a reply after its proven flush boundary is invalid");
            assertTrue(secondProof > firstProof);
        }
    }

    @Test
    void leadingPongDoesNotProveTheChallengeArrived() throws Exception {
        try (var f = new Fixture()) {
            int proof = f.send(1);
            f.prove(proof - 1);
            f.tickEnd();
            f.reply(1);
            assertEquals(0, f.check.getViolations());
        }
    }

    @Test
    void noProofDoesNotInventAnExpiry() throws Exception {
        try (var f = new Fixture()) {
            // A transition can prevent KeepAliveProcessor from sending a trailing transaction.
            var packet = new ClientboundKeepAlive(1);
            var event = new PacketSendEvent<>(
                    f.network.user, ConnectionPhase.PLAY, ClientboundPackets.KEEP_ALIVE, packet, false);
            f.check.onKeepAlive(event, f.network.player, packet);
            event.tasksAfter().forEach(Runnable::run);
            f.prove(100);
            f.tickEnd();
            f.reply(1);
            assertEquals(0, f.check.getViolations());
        }
    }

    @Test
    void tickProgressRetiresUnansweredIdsInsteadOfAccumulatingThem() throws Exception {
        try (var f = new Fixture()) {
            for (int i = 0; i < 256; i++) {
                f.send(i * 2L);
                int proof = f.send(i * 2L + 1);
                f.reply(i * 2L + 1);
                f.prove(proof);
                f.tickEnd();
                assertEquals(0, f.pending());
            }
            assertEquals(0, f.check.getViolations());
        }
    }

    @Test
    void noTickProgressDoesNotEvictOlderIdsAtAnArbitrarySize() throws Exception {
        try (var f = new Fixture()) {
            for (int i = 0; i < 128; i++) f.send(i);
            f.prove(f.network.player.lastTransactionSent.get());
            for (int i = 127; i >= 0; i--) f.reply(i);
            assertEquals(0, f.check.getViolations());
            assertEquals(0, f.pending());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final RecordConsumerServices services = new RecordConsumerServices();
        private final RecordReceiveFixture network = new RecordReceiveFixture(ProtocolVersion.V26_3);
        private final BadPacketsO check = network.player.checkManager.getCheck(BadPacketsO.class);

        private Fixture() throws Exception {
            services.useNetwork(network.manager);
            network.user.bind(mock(PlatformPlayer.class));
            network.player.nettyScheduler.removeScheduler();
        }

        private int send(long id) {
            var packet = new ClientboundKeepAlive(id);
            var event = new PacketSendEvent<>(
                    network.user, ConnectionPhase.PLAY, ClientboundPackets.KEEP_ALIVE, packet, false);
            network.player.checkManager.dispatchSendHandlers(event);
            event.tasksAfter().forEach(Runnable::run);
            assertTrue(event.receiptProof() > 0);
            return event.receiptProof();
        }

        private PacketReceiveEvent<ServerboundKeepAlive> reply(long id) {
            var packet = new ServerboundKeepAlive(id);
            var event =
                    new PacketReceiveEvent<>(network.user, ConnectionPhase.PLAY, ServerboundPackets.KEEP_ALIVE, packet);
            check.onKeepAlive(event, network.player, packet);
            return event;
        }

        private void prove(int transaction) {
            network.player.lastTransactionReceived.set(transaction);
        }

        private void tickEnd() {
            var packet = new Opaque(ServerboundPackets.CLIENT_TICK_END);
            // Exercise registration of BadPacketsO's new packet handler in the production check manager.
            network.player.checkManager.dispatchReceiveHandlers(new PacketReceiveEvent<>(
                    network.user, ConnectionPhase.PLAY, ServerboundPackets.CLIENT_TICK_END, packet));
        }

        private int pending() throws ReflectiveOperationException {
            var field = BadPacketsO.class.getDeclaredField("keepalives");
            field.setAccessible(true);
            return ((Collection<?>) field.get(check)).size();
        }

        @Override
        public void close() throws Exception {
            network.close();
            services.close();
        }
    }
}
