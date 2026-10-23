package ac.cult.cultac.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bedrock.replay.offline.OfflinePlayers;
import ac.cult.cultac.events.packets.listeners.PacketPingListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;

class WireIdTest {
    private static final int FOREIGN = Short.MIN_VALUE - 5;
    private static final int FRESH = Short.MIN_VALUE - 6;

    @Test
    void pingIdsAreNotSequential() {
        var random = words(new SecureRandom());
        Set<Integer> seen = new HashSet<>();
        int steps = 0;
        int previous = CultPlayer.nextWireId(random);
        for (int i = 0; i < 1000; i++) {
            int id = CultPlayer.nextWireId(random);
            if (Math.abs((long) id - previous) <= 1) steps++;
            seen.add(id);
            previous = id;
        }
        assertTrue(steps < 3, "ids stepped by one " + steps + " times");
        assertTrue(seen.size() > 990, "only " + seen.size() + " distinct ids");
    }

    @Test
    void pingIdsStayBelowTheSignedShortRange() {
        assertEquals(
                Short.MIN_VALUE - 1, CultPlayer.nextWireId(scripted(-1, Short.MIN_VALUE, -12345, Short.MIN_VALUE - 1)));

        var random = words(new SecureRandom());
        for (int i = 0; i < 100_000; i++) {
            int id = CultPlayer.nextWireId(random);
            assertTrue(id <= Short.MIN_VALUE - 1, "id " + id);
        }
    }

    @Test
    void aBackendPingInFlightIsNeverReused() {
        withPlayer(player -> {
            player.foreignPingSent(FOREIGN);
            assertEquals(FRESH, player.nextTransactionId(scripted(FOREIGN, FRESH)));
        });
    }

    @Test
    void theOldestBackendPingIsForgottenPastTheBound() {
        withPlayer(player -> {
            player.foreignPingSent(FOREIGN);
            for (int i = 0; i < 1024; i++) player.foreignPingSent(FRESH - 1 - i);
            assertEquals(FOREIGN, player.nextTransactionId(scripted(FOREIGN)));
            assertEquals(FRESH, player.nextTransactionId(scripted(FRESH - 1, FRESH)));
        });
    }

    @Test
    void aBackendPingIsTrackedFromItsSendUntilItsPong() {
        withPlayer(player -> {
            var listener = new PacketPingListener();
            var ping = new ClientboundPing(FOREIGN);
            listener.onPing(
                    new PacketSendEvent<>(player.user, ConnectionPhase.PLAY, ClientboundPackets.PING, ping, false),
                    player,
                    ping);
            assertEquals(FRESH, player.nextTransactionId(scripted(FOREIGN, FRESH)));

            var pong = new ServerboundPong(FOREIGN);
            listener.onPong(
                    new PacketReceiveEvent<>(player.user, ConnectionPhase.PLAY, ServerboundPackets.PONG, pong),
                    player,
                    pong);
            assertEquals(FOREIGN, player.nextTransactionId(scripted(FOREIGN)));
        });
    }

    @Test
    void idsAreTheWordsSecureRandomNextIntReadsFromTheSameByteStream() {
        var perInt = new ByteStream();
        var blocks = new ByteStream();
        var ids = words(blocks);
        int count = 3 * WireIds.BLOCK_BYTES / 4 + 7;
        for (int i = 0; i < count; i++) assertEquals(perInt.nextInt(), ids.getAsInt());
        assertEquals(4, blocks.draws);
        assertEquals(4L * count, perInt.position);
        assertEquals(4L * WireIds.BLOCK_BYTES, blocks.position);
    }

    @Test
    void theSameIdsComeFromTheSameDrbgOutput() throws Exception {
        var seed = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
        var direct = SecureRandom.getInstance("SHA1PRNG");
        direct.setSeed(seed);
        var buffered = SecureRandom.getInstance("SHA1PRNG");
        buffered.setSeed(seed);
        var ids = words(buffered);
        var block = new byte[WireIds.BLOCK_BYTES];
        for (int round = 0; round < 4; round++) {
            direct.nextBytes(block);
            for (int i = 0; i < block.length; i += 4) {
                int word = (block[i] << 24)
                        | (block[i + 1] & 0xFF) << 16
                        | (block[i + 2] & 0xFF) << 8
                        | (block[i + 3] & 0xFF);
                assertEquals(word, ids.getAsInt());
            }
        }
    }

    @Test
    void aSeededSha1PrngGivesTheIdsItsNextIntWould() throws Exception {
        var seed = new byte[] {9, 8, 7, 6};
        var direct = SecureRandom.getInstance("SHA1PRNG");
        direct.setSeed(seed);
        var buffered = SecureRandom.getInstance("SHA1PRNG");
        buffered.setSeed(seed);
        var ids = words(buffered);
        for (int i = 0; i < 5 * WireIds.BLOCK_BYTES; i++)
            assertEquals(CultPlayer.nextWireId(direct::nextInt), CultPlayer.nextWireId(ids));
    }

    @Test
    void anotherSourceIsReadFromItsFirstByte() {
        var block = new WireIds();
        var first = new ByteStream();
        block.next(first);
        var replaced = new ByteStream();
        var reference = new ByteStream();
        for (int i = 0; i < 70; i++) assertEquals(reference.nextInt(), block.next(replaced));
    }

    /** A deterministic byte stream that is the same however its reads are split. */
    private static final class ByteStream extends SecureRandom {
        long position;
        int draws;

        @Override
        public void nextBytes(byte[] bytes) {
            draws++;
            for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (position++ * 0x9E3779B97F4A7C15L >>> 56);
        }
    }

    private static IntSupplier words(SecureRandom random) {
        var block = new WireIds();
        return () -> block.next(random);
    }

    private static IntSupplier scripted(int... script) {
        int[] next = {0};
        return () -> script[next[0]++];
    }

    private static void withPlayer(Consumer<CultPlayer> body) {
        OfflinePlayers.installConfig();
        CultPlayer player = OfflinePlayers.player();
        try {
            body.accept(player);
        } finally {
            OfflinePlayers.close(player);
        }
    }
}
