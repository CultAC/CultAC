package ac.cult.cultac.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.bedrock.replay.offline.OfflinePlayers;
import ac.cult.cultac.events.packets.listeners.PacketPingListener;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransactionIdTest {
    private static final int FRESH = Short.MIN_VALUE - 1;

    /** Random sends, writes and pongs, known and unknown; every in-flight id stays taken and nothing else does. */
    @Test
    void inFlightIdsAreRejectedThroughEverySendAndAcknowledgementOnJava() {
        OfflinePlayers.installConfig();
        User user = TestUsers.create(new User.Profile(UUID.randomUUID(), ".Transaction_Ids"), new EmbeddedChannel());
        CultPlayer player = new CultPlayer(user);
        player.user.bind(mock(PlatformPlayer.class));
        try {
            assertFalse(player.isBedrockMovement());
            new Model(player, new Random(0x1D5L)).run(20_000);
        } finally {
            OfflinePlayers.close(player);
        }
    }

    /** As on Java, with native boundaries inserted into the written queue and backend pings in flight. */
    @Test
    void inFlightIdsAreRejectedThroughNativeBoundariesAndForeignPingsOnBedrock() {
        OfflinePlayers.installConfig();
        CultPlayer player = OfflinePlayers.player();
        player.user.bind(mock(PlatformPlayer.class));
        try {
            assertTrue(player.isBedrockMovement());
            new Model(player, new Random(0xB3DL)).run(20_000);
        } finally {
            OfflinePlayers.close(player);
        }
    }

    /** The written queue in wire order, kept by the old rules; the ids it holds are what the old scan rejected. */
    private static final class Model {
        private final CultPlayer player;
        private final Random random;
        private final PacketPingListener listener = new PacketPingListener();
        private final Map<Integer, CultPlayer.TrackedTransaction> pending = new HashMap<>();
        private final List<Integer> sent = new ArrayList<>();
        private final Set<Integer> foreign = new LinkedHashSet<>();
        private final Set<Integer> answered = new LinkedHashSet<>();
        private Integer lastClientbound;
        private int nextForeign = FRESH - 1;
        private int boundaries, probes;

        Model(CultPlayer player, Random random) {
            this.player = player;
            this.random = random;
        }

        void run(int steps) {
            boolean bedrock = player.isBedrockMovement();
            for (int step = 0; step < steps; step++) {
                int action = random.nextInt(bedrock ? 14 : 10);
                if (action < 4 || pending.isEmpty() && sent.isEmpty()) {
                    var transaction = player.createTrackedTransactionPacketForDeferredSend();
                    assertNotNull(transaction);
                    pending.put(transaction.id(), transaction);
                } else if (action < 6 && !pending.isEmpty()) {
                    int id = List.copyOf(pending.keySet()).get(random.nextInt(pending.size()));
                    var transaction = pending.remove(id);
                    if (random.nextBoolean()) player.markTrackedTransactionPacketSent(transaction);
                    else assertTrue(player.markTransactionPacketSent(id, 0));
                    sent.add(id);
                } else if (action < 9) {
                    pongSomething();
                } else if (action == 9) {
                    foreignPing();
                } else if (action < 12) {
                    boundary();
                } else if (!sent.isEmpty()) {
                    int id = sent.get(random.nextInt(sent.size()));
                    player.markBedrockTransactionClientbound(id);
                    lastClientbound = id;
                }
                if (step % 7 == 0) probe(step);
            }
            assertTrue(probes > 2000);
            if (bedrock) assertTrue(boundaries > 1000);
        }

        private void pongSomething() {
            int id;
            int pick = random.nextInt(4);
            if (pick == 0 && !sent.isEmpty()) id = sent.get(random.nextInt(sent.size()));
            else if (pick == 1 && !pending.isEmpty())
                id = List.copyOf(pending.keySet()).getFirst();
            else if (pick == 2 && !foreign.isEmpty()) id = List.copyOf(foreign).get(random.nextInt(foreign.size()));
            else id = FRESH - 5000 - random.nextInt(1000);
            var pong = new ServerboundPong(id);
            listener.onPong(
                    new PacketReceiveEvent<>(player.user, ConnectionPhase.PLAY, ServerboundPackets.PONG, pong),
                    player,
                    pong);
            int index = sent.indexOf(id);
            if (index >= 0) {
                answered.addAll(sent.subList(0, index + 1));
                sent.subList(0, index + 1).clear();
            } else foreign.remove(id);
        }

        private void foreignPing() {
            if (foreign.size() >= 64) return;
            var ping = new ClientboundPing(nextForeign--);
            listener.onPing(
                    new PacketSendEvent<>(player.user, ConnectionPhase.PLAY, ClientboundPackets.PING, ping, false),
                    player,
                    ping);
            foreign.add(ping.id());
        }

        private void boundary() {
            if (!player.isBedrockMovement()) return;
            var transaction = player.createBedrockTransactionAfterClientbound();
            int index = lastClientbound == null ? -1 : sent.indexOf(lastClientbound);
            sent.add(index + 1, transaction.id());
            boundaries++;
        }

        private void probe(int step) {
            var candidates = new ArrayList<Integer>();
            candidates.addAll(pending.keySet());
            candidates.addAll(sent);
            candidates.addAll(foreign);
            candidates.addAll(answered);
            Collections.shuffle(candidates, random);
            var script = new ArrayList<>(candidates.subList(0, Math.min(candidates.size(), 64)));
            int expected = FRESH;
            for (int id : script) {
                if (!pending.containsKey(id) && !sent.contains(id) && !foreign.contains(id)) {
                    expected = id;
                    break;
                }
            }
            script.add(FRESH);
            int[] next = {0};
            assertEquals(expected, player.nextTransactionId(() -> script.get(next[0]++)), "step " + step);
            probes++;
        }
    }
}
