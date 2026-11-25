package ac.cult.cultac.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.bedrock.replay.offline.OfflinePlayers;
import ac.cult.cultac.checks.impl.ping.TransactionOrder;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * CultPlayer's transaction bookkeeping against the base implementation, kept verbatim below as {@link Base}: the
 * same sends, writes, pongs (in order, out of order, duplicated, forged, never), native boundaries and their
 * callbacks, latency tasks (some sending again from inside), and platform switches. After every step the return
 * values, the callback order, the received and sent transactions, the player clock and the ids in use match.
 */
class TransactionLedgerEquivalenceTest {
    private static final int FRESH = Short.MIN_VALUE - 1;

    @Test
    void javaPlayerMatchesTheBaseLedger() {
        OfflinePlayers.installConfig();
        for (long seed = 1; seed <= 4; seed++) {
            User user = TestUsers.create(new User.Profile(UUID.randomUUID(), ".Ledger_Java"), new EmbeddedChannel());
            CultPlayer player = new CultPlayer(user);
            player.user.bind(mock(PlatformPlayer.class));
            try {
                new Run(player, new Random(seed), false).run(6_000);
            } finally {
                OfflinePlayers.close(player);
            }
        }
    }

    @Test
    void switchingPlatformsMatchesTheBaseLedger() {
        OfflinePlayers.installConfig();
        for (long seed = 11; seed <= 14; seed++) {
            CultPlayer player = OfflinePlayers.player();
            player.user.bind(mock(PlatformPlayer.class));
            player.movementPlatform = MovementPlatform.JAVA;
            try {
                new Run(player, new Random(seed), true).run(6_000);
            } finally {
                OfflinePlayers.close(player);
            }
        }
    }

    /** One random sequence, applied to the player and to {@link Base}, compared after every step. */
    private static final class Run {
        private final CultPlayer player;
        private final Random random;
        private final boolean switches;
        private final Base base = new Base();
        private final PacketPingListener listener = new PacketPingListener();
        private final List<String> playerLog = new ArrayList<>();
        private final List<Integer> created = new ArrayList<>();
        private final List<Integer> written = new ArrayList<>();
        private final Set<Integer> foreign = new LinkedHashSet<>();
        private final Map<Integer, CultPlayer.BedrockTransaction> boundaries = new HashMap<>();
        private final Map<Integer, Integer> reentrantIds = new HashMap<>();
        // The nanoTime bounds of the call that stamped each entry, by id.
        private final Map<Integer, long[]> stamped = new HashMap<>();
        private int nextForeign = FRESH - 1;
        private int tasks;
        private int responses, boundaryCount, reentrant;

        Run(CultPlayer player, Random random, boolean switches) {
            this.player = player;
            this.random = random;
            this.switches = switches;
            player.keepAliveProcessor.lastKeepAlivePing = 1L;
            player.checkManager.allChecks.put(TransactionOrder.class, new LoggingOrder(player, playerLog));
            base.lastSent = player.lastTransactionSent.get();
            base.lastReceived = player.lastTransactionReceived.get();
            base.bedrock = player.isBedrockMovement();
        }

        void run(int steps) {
            for (int step = 0; step < steps; step++) {
                int action = random.nextInt(switches ? 16 : 12);
                if (action < 3 || created.isEmpty() && written.isEmpty()) create();
                else if (action < 6 && !created.isEmpty()) write();
                else if (action < 9) pong();
                else if (action == 9) foreignPing();
                else if (action == 10) latencyTask();
                else if (action == 11) probe();
                else if (action == 12) switchPlatform();
                else if (action < 15) boundary();
                else markClientbound();
                compare(step);
            }
            assertTrue(responses > 500, "responses " + responses);
            long skips = playerLog.stream()
                    .filter(line -> line.startsWith("skipped"))
                    .count();
            assertTrue(skips > 50, "skips " + skips);
            assertTrue(reentrant > 20, "re-entrant sends " + reentrant);
            if (switches) assertTrue(boundaryCount > 100, "boundaries " + boundaryCount);
        }

        private void create() {
            var transaction = player.createTrackedTransactionPacketForDeferredSend();
            assertNotNull(transaction);
            base.create(transaction.transaction(), transaction.id());
            created.add(transaction.id());
        }

        private void write() {
            int id = created.remove(random.nextInt(created.size()));
            int how = random.nextInt(3);
            long before = System.nanoTime();
            int transaction = mark(id, how);
            long after = System.nanoTime();
            stamped.put(id, new long[] {before, after});
            int expected = base.mark(id);
            assertEquals(how == 1 ? (expected > 0 ? 1 : -1) : expected, transaction, "write " + id);
            written.add(id);
        }

        private int mark(int id, int how) {
            return switch (how) {
                case 0 -> player.markTrackedPingSent(id, 0);
                case 1 -> player.markTransactionPacketSent(id, 0) ? 1 : -1;
                default -> {
                    var ping = new ClientboundPing(id);
                    var event = new PacketSendEvent<>(
                            player.user, ConnectionPhase.PLAY, ClientboundPackets.PING, ping, false);
                    listener.onPing(event, player, ping);
                    yield event.trackedPing() > 0 ? event.trackedPing() : -1;
                }
            };
        }

        private void pong() {
            int id;
            int pick = random.nextInt(10);
            if (pick < 5 && !written.isEmpty()) id = written.get(Math.min(written.size() - 1, random.nextInt(3)));
            else if (pick < 7 && !written.isEmpty()) id = written.get(random.nextInt(written.size()));
            else if (pick == 7 && !created.isEmpty()) id = created.get(random.nextInt(created.size()));
            else if (pick == 8 && !foreign.isEmpty()) id = List.copyOf(foreign).get(random.nextInt(foreign.size()));
            else id = FRESH - 5000 - random.nextInt(1000);
            long clock = player.getPlayerClockAtLeast();
            var pong = new ServerboundPong(id);
            var event = new PacketReceiveEvent<>(player.user, ConnectionPhase.PLAY, ServerboundPackets.PONG, pong);
            listener.onPong(event, player, pong);
            Integer acknowledged = base.response(id);
            assertEquals(acknowledged != null, event.isAcceptedTransactionResponse(), "pong " + id);
            if (acknowledged != null) {
                responses++;
                long[] bounds = stamped.get(acknowledged);
                long now = player.getPlayerClockAtLeast();
                assertTrue(now >= bounds[0] && now <= bounds[1], "clock of " + acknowledged);
            } else {
                assertEquals(clock, player.getPlayerClockAtLeast());
                foreign.remove(id);
            }
            written.removeIf(base::answered);
        }

        private void foreignPing() {
            if (foreign.size() >= 32) return;
            var ping = new ClientboundPing(nextForeign--);
            listener.onPing(
                    new PacketSendEvent<>(player.user, ConnectionPhase.PLAY, ClientboundPackets.PING, ping, false),
                    player,
                    ping);
            base.foreign.add(ping.id());
            foreign.add(ping.id());
        }

        private void latencyTask() {
            int task = tasks++;
            boolean sends = random.nextInt(4) == 0;
            int at = player.lastTransactionSent.get() + random.nextInt(2);
            player.latencyUtils.addRealTimeTask(at, () -> {
                playerLog.add("task " + task);
                if (sends) {
                    var transaction = player.createTrackedTransactionPacketForDeferredSend();
                    long before = System.nanoTime();
                    player.markTransactionPacketSent(transaction.id(), 0);
                    long after = System.nanoTime();
                    stamped.put(transaction.id(), new long[] {before, after});
                    reentrantIds.put(task, transaction.id());
                    written.add(transaction.id());
                }
            });
            base.addTask(at, () -> {
                base.log.add("task " + task);
                if (sends) {
                    Integer id = reentrantIds.get(task);
                    assertNotNull(id, "task " + task + " sent nothing");
                    base.create(base.lastSent + 1, id);
                    base.mark(id);
                    reentrant++;
                }
            });
        }

        private void probe() {
            var candidates = new ArrayList<Integer>();
            candidates.addAll(created);
            candidates.addAll(written);
            candidates.addAll(foreign);
            for (int i = 0; i < 4; i++) candidates.add(FRESH - 7000 - random.nextInt(500));
            Collections.shuffle(candidates, random);
            var script = new ArrayList<>(candidates.subList(0, Math.min(candidates.size(), 32)));
            for (int id : script) assertEquals(base.awaits(id), player.awaitsTransactionResponse(id), "awaits " + id);
            int expected = FRESH;
            for (int id : script) {
                if (!base.used(id)) {
                    expected = id;
                    break;
                }
            }
            script.add(FRESH);
            int[] next = {0};
            assertEquals(expected, player.nextTransactionId(() -> script.get(next[0]++)));
        }

        private void switchPlatform() {
            if (!switches) return;
            base.bedrock = !base.bedrock;
            player.movementPlatform = base.bedrock ? MovementPlatform.BEDROCK : MovementPlatform.JAVA;
        }

        private void boundary() {
            if (!switches || !base.bedrock) return;
            long before = System.nanoTime();
            var transaction = player.createBedrockTransactionAfterClientbound();
            long after = System.nanoTime();
            stamped.put(transaction.id(), new long[] {before, after});
            assertEquals(base.boundary(transaction.id()), transaction.transaction(), "boundary");
            boundaries.put(transaction.id(), transaction);
            boundaryCount++;
            int callbacks = random.nextInt(3);
            for (int i = 0; i < callbacks; i++) {
                int task = tasks++;
                player.addBedrockTransactionTask(transaction, () -> playerLog.add("native " + task));
                base.addNativeTask(transaction.id(), () -> base.log.add("native " + task));
            }
            written.add(transaction.id());
        }

        private void markClientbound() {
            if (!switches || written.isEmpty()) return;
            int id = written.get(random.nextInt(written.size()));
            player.markBedrockTransactionClientbound(id);
            base.markClientbound(id);
        }

        private void compare(int step) {
            assertEquals(base.log, playerLog, "callbacks at step " + step);
            assertEquals(base.lastReceived, player.lastTransactionReceived.get(), "received at step " + step);
            assertEquals(base.lastSent, player.lastTransactionSent.get(), "sent at step " + step);
            if (step % 97 == 0) {
                for (var entry : boundaries.entrySet()) {
                    if (!base.answered(entry.getKey())) assertSame(entry.getValue(), boundaryOf(entry.getKey()));
                }
            }
        }

        private CultPlayer.BedrockTransaction boundaryOf(int id) {
            player.markBedrockTransactionClientbound(id);
            base.markClientbound(id);
            return player.getLastClientboundBedrockTransaction();
        }
    }

    private static final class LoggingOrder extends TransactionOrder {
        private final List<String> log;

        LoggingOrder(CultPlayer player, List<String> log) {
            super(player);
            this.log = log;
        }

        @Override
        public void skipped(int skipped) {
            log.add("skipped " + skipped);
        }
    }

    /** The base bookkeeping of sends, writes, pongs and native boundaries, with its callbacks logged. */
    private static final class Base {
        final List<String> log = new ArrayList<>();
        final Set<Integer> foreign = new LinkedHashSet<>();
        final Map<Integer, Integer> pendingSend = new HashMap<>();
        final LinkedList<Entry> sent = new LinkedList<>();
        final Map<Integer, Entry> sentById = new HashMap<>();
        final Set<Integer> answeredIds = new HashSet<>();
        final List<int[]> taskAt = new ArrayList<>();
        final List<Runnable> tasks = new ArrayList<>();
        Entry lastClientbound;
        boolean bedrock;
        int lastSent, lastReceived;

        static final class Entry {
            final int transaction, id;
            final boolean bedrock;
            boolean acknowledged;
            List<Runnable> acknowledgements;

            Entry(int transaction, int id, boolean bedrock) {
                this.transaction = transaction;
                this.id = id;
                this.bedrock = bedrock;
            }
        }

        void create(int transaction, int id) {
            assertTrue(!used(id), "id " + id + " was in use");
            assertEquals(++lastSent, transaction, "transaction of " + id);
            answeredIds.remove(id);
            pendingSend.put(id, transaction);
        }

        int mark(int id) {
            Integer transaction = pendingSend.remove(id);
            if (transaction == null) return -1;
            Entry entry = new Entry(transaction, id, bedrock);
            sent.add(entry);
            sentById.put(id, entry);
            return transaction;
        }

        int boundary(int id) {
            assertTrue(!used(id), "boundary id " + id + " was in use");
            answeredIds.remove(id);
            Entry previous = lastClientbound;
            Entry entry = new Entry(previous == null ? lastReceived : previous.transaction, id, true);
            if (previous == null || previous.acknowledged) sent.addFirst(entry);
            else {
                int index = sent.indexOf(previous);
                if (index < 0) throw new IllegalStateException("Last written transaction is missing");
                sent.add(index + 1, entry);
            }
            sentById.put(id, entry);
            return entry.transaction;
        }

        void addNativeTask(int id, Runnable task) {
            Entry entry = sentById.get(id);
            if (entry == null || entry.acknowledged) task.run();
            else {
                if (entry.acknowledgements == null) entry.acknowledgements = new ArrayList<>(2);
                entry.acknowledgements.add(task);
            }
        }

        void markClientbound(int id) {
            Entry entry = sentById.get(id);
            if (entry != null && entry.bedrock) lastClientbound = entry;
        }

        /** The id whose entry stamped the player clock, or null when the pong is not CultAC's. */
        Integer response(int id) {
            Entry answered = sentById.get(id);
            if (answered == null) {
                foreign.remove(id);
                return null;
            }
            if (!bedrock && sent.peek() != answered) {
                int skipped = 0;
                for (Entry pending : sent) {
                    if (pending == answered) break;
                    skipped++;
                }
                log.add("skipped " + skipped);
            }
            Entry current;
            do {
                current = sent.poll();
                sentById.remove(current.id);
                answeredIds.add(current.id);
                lastReceived = current.transaction;
                if (current.bedrock) {
                    drain(current.transaction);
                    current.acknowledged = true;
                    var callbacks = current.acknowledgements;
                    current.acknowledgements = null;
                    if (callbacks != null) callbacks.forEach(Runnable::run);
                }
            } while (current.id != id);
            drain(lastReceived);
            return current.id;
        }

        boolean answered(int id) {
            return answeredIds.contains(id);
        }

        void addTask(int transaction, Runnable task) {
            if (lastReceived >= transaction) task.run();
            else {
                taskAt.add(new int[] {transaction});
                tasks.add(task);
            }
        }

        void drain(int transaction) {
            var ready = new ArrayList<Runnable>();
            for (int i = 0; i < tasks.size(); ) {
                if (taskAt.get(i)[0] <= transaction) {
                    ready.add(tasks.remove(i));
                    taskAt.remove(i);
                } else i++;
            }
            ready.forEach(Runnable::run);
        }

        boolean awaits(int id) {
            return pendingSend.containsKey(id) || sentById.containsKey(id);
        }

        boolean used(int id) {
            return sentById.containsKey(id) || pendingSend.containsKey(id) || foreign.contains(id);
        }
    }
}
