package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The order LatencyTaskQueue runs tasks in. Movement-like traffic, where no task is more than one below a task queued
 * before it, runs in the order of the single-list scan the queue replaced. A task added two or more below a queued
 * task, such as a Bedrock receipt callback for an earlier Java proof, goes before the first such task rather than at
 * the end, so one drain that reaches both runs it first. Random scripts check that order against a full-scan model of
 * it: tasks at past, present, future and extreme transactions, continuations, drains that skip, repeat or go
 * backwards, tasks that enqueue or drain again while their batch runs, and tasks that throw part way through a batch.
 */
class LatencyTaskQueueOrderTest {
    private int lateInserts;
    private int stoppingDrains;
    private int readyBehindWaiting;
    private int deferredContinuations;
    private int readyContinuations;

    @Test
    void aLateCallbackRunsBeforeTheFirstTaskTwoAboveIt() {
        var queue = new LatencyTaskQueue();
        var log = new ArrayList<String>();
        queue.add(23, () -> log.add("A"));
        queue.add(100, () -> log.add("B"));
        queue.add(23, () -> log.add("C"));
        drain(queue, 100);
        assertEquals(List.of("A", "C", "B"), log);

        // The scan queue appended C and ran it last.
        var scan = new ScanLatencyTaskQueue();
        var scanLog = new ArrayList<String>();
        scan.add(23, () -> scanLog.add("A"));
        scan.add(100, () -> scanLog.add("B"));
        scan.add(23, () -> scanLog.add("C"));
        drain(scan, 100);
        assertEquals(List.of("A", "B", "C"), scanLog);
    }

    @Test
    void aTaskOneBelowAQueuedTaskKeepsInsertionOrder() {
        var queue = new LatencyTaskQueue();
        var log = new ArrayList<String>();
        queue.add(10, () -> log.add("A"));
        queue.add(11, () -> log.add("B"));
        queue.add(10, () -> log.add("C"));
        drain(queue, 11);
        assertEquals(List.of("A", "B", "C"), log);
    }

    @Test
    void aDrainThatSkipsTransactionsRunsEachReadyContinuationRightAfterItsTask() {
        var queue = new LatencyTaskQueue();
        var log = new ArrayList<String>();
        queue.addWithNextTransaction(10, () -> log.add("a"), () -> log.add("a'"));
        queue.add(11, () -> log.add("b"));
        queue.addWithNextTransaction(11, () -> log.add("c"), () -> log.add("c'"));
        queue.add(12, () -> log.add("d"));
        queue.add(14, () -> log.add("e"));
        drain(queue, 12);
        assertEquals(List.of("a", "a'", "b", "c", "c'", "d"), log);
        drain(queue, 14);
        assertEquals(List.of("a", "a'", "b", "c", "c'", "d", "e"), log);
    }

    @Test
    void aContinuationThatIsNotReadyRunsInItsTasksPlace() {
        var queue = new LatencyTaskQueue();
        var log = new ArrayList<String>();
        queue.addWithNextTransaction(10, () -> log.add("a"), () -> log.add("a'"));
        queue.add(11, () -> log.add("b"));
        drain(queue, 10);
        assertEquals(List.of("a"), log);
        drain(queue, 11);
        assertEquals(List.of("a", "a'", "b"), log);
    }

    @Test
    void aLateCallbackRunsInADrainThatSkipsPastIt() {
        var queue = new LatencyTaskQueue();
        var log = new ArrayList<String>();
        queue.addWithNextTransaction(23, () -> log.add("A"), () -> log.add("A'"));
        queue.add(100, () -> log.add("B"));
        queue.add(22, () -> log.add("C"));
        drain(queue, 24);
        assertEquals(List.of("A", "A'", "C"), log);
        drain(queue, 100);
        assertEquals(List.of("A", "A'", "C", "B"), log);
    }

    @Test
    void movementLikeTrafficRunsInTheScanOrder() {
        int skippingDrains = 0;
        for (long seed = 0; seed < 200; seed++) skippingDrains += traffic(seed, 4_000);
        assertTrue(skippingDrains > 0, "no drain skipped a transaction");
    }

    @Test
    void randomScriptsRunInTheModelOrder() {
        for (long seed = 0; seed < 600; seed++) compare(seed, 2_500);
        assertTrue(lateInserts > 0, "no task went before a queued task");
        assertTrue(stoppingDrains > 0, "no drain reached a task two past the answered transaction");
        assertTrue(readyBehindWaiting > 0, "no ready task waited behind one that was not ready");
        assertTrue(deferredContinuations > 0, "no continuation waited in its task's place");
        assertTrue(readyContinuations > 0, "no continuation ran in its task's drain");
    }

    private void compare(long seed, int steps) {
        Random random = new Random(seed);
        Driver model = new Driver(new ModelQueue(), seed);
        Driver candidate = new Driver(new CurrentQueue(), seed);
        for (int step = 0; step < steps; step++) {
            long op = random.nextLong();
            model.apply(op);
            candidate.apply(op);
            assertEquals(model.log, candidate.log, "seed=" + seed + " step=" + step);
        }
        candidate.queue.drain(Integer.MAX_VALUE, candidate);
        model.queue.drain(Integer.MAX_VALUE, model);
        assertEquals(model.log, candidate.log, "seed=" + seed + " final");
    }

    /**
     * Pongs in order, sometimes skipping, and tasks at the next transaction or the one after, a third of them with a
     * continuation. The sent count only grows, so no task is more than one below a task queued before it. Returns the
     * number of drains that skipped a transaction.
     */
    private int traffic(long seed, int steps) {
        List<List<Integer>> logs = new ArrayList<>();
        int skippingDrains = 0;
        for (Queue queue : List.of(new ScanQueue(), new CurrentQueue())) {
            Random script = new Random(seed);
            List<Integer> log = new ArrayList<>();
            int sent = 0;
            int received = 0;
            int id = 0;
            skippingDrains = 0;
            for (int step = 0; step < steps; step++) {
                int kind = script.nextInt(10);
                if (kind < 3) {
                    sent++;
                } else if (kind < 7) {
                    int task = id++;
                    int at = sent + 1 + script.nextInt(2);
                    if (script.nextInt(3) == 0) queue.add(sent + 1, () -> log.add(task));
                    else if (script.nextBoolean()) queue.addNext(at, () -> log.add(task), () -> log.add(-task - 1));
                    else queue.add(at, () -> log.add(task));
                } else if (received < sent) {
                    int skipped = script.nextInt(Math.min(3, sent - received));
                    if (skipped > 0) skippingDrains++;
                    received += 1 + skipped;
                    queue.drain(received, null);
                }
            }
            queue.drain(Integer.MAX_VALUE, null);
            logs.add(log);
        }
        assertEquals(logs.get(0), logs.get(1), "seed=" + seed);
        return skippingDrains;
    }

    private static void drain(LatencyTaskQueue queue, int transaction) {
        var node = queue.drainReady(transaction);
        LatencyTaskQueue.TaskNode released = null;
        try {
            while (node != null) {
                var next = node.next();
                var task = node.runnable();
                released = node.clearAndLink(released);
                task.run();
                node = next;
            }
        } finally {
            queue.recycle(released);
        }
    }

    private static void drain(ScanLatencyTaskQueue queue, int transaction) {
        var node = queue.drainReady(transaction);
        ScanLatencyTaskQueue.TaskNode released = null;
        try {
            while (node != null) {
                var next = node.next();
                var task = node.runnable();
                released = node.clearAndLink(released);
                task.run();
                node = next;
            }
        } finally {
            queue.recycle(released);
        }
    }

    private interface Queue {
        void add(int transaction, Runnable task);

        void addNext(int transaction, Runnable task, Runnable next);

        /** Drains and runs the batch; a throwing task ends the batch and the rest is dropped. */
        void drain(int transaction, Driver driver);
    }

    /** One queue driven by the shared script; task behaviour depends only on the seed and the task id. */
    private static final class Driver {
        final Queue queue;
        final long seed;
        final List<Integer> log = new ArrayList<>();
        int sent;
        int received;
        int nextId;
        int depth;

        Driver(Queue queue, long seed) {
            this.queue = queue;
            this.seed = seed;
        }

        void apply(long op) {
            Random random = new Random(op);
            int kind = random.nextInt(100);
            if (kind < 40) {
                enqueue(random, sent);
            } else if (kind < 52) {
                sent += 1 + random.nextInt(2);
            } else {
                int transaction = switch (random.nextInt(20)) {
                    case 0 -> received - random.nextInt(4);
                    case 1 -> Integer.MAX_VALUE;
                    case 2 -> received + 5 + random.nextInt(10);
                    default -> received + random.nextInt(4);
                };
                received = Math.max(received, transaction == Integer.MAX_VALUE ? received : transaction);
                queue.drain(transaction, this);
            }
        }

        void enqueue(Random random, int base) {
            int transaction = switch (random.nextInt(25)) {
                case 0 -> Integer.MAX_VALUE;
                case 1 -> Integer.MAX_VALUE - 1;
                case 2 -> Integer.MIN_VALUE;
                case 3 -> base + 20 + random.nextInt(40);
                case 4, 5, 6 -> base - random.nextInt(6);
                default -> base + random.nextInt(4);
            };
            int id = nextId++;
            if (random.nextInt(3) == 0) {
                queue.addNext(transaction, task(id), () -> log.add(-id - 1));
            } else {
                queue.add(transaction, task(id));
            }
        }

        private Runnable task(int id) {
            return () -> {
                log.add(id);
                Random random = new Random(seed * 1_000_003L + id);
                int roll = random.nextInt(100);
                if (roll < 2) throw new IllegalStateException("abort " + id);
                if (roll < 22) {
                    int count = 1 + random.nextInt(3);
                    for (int i = 0; i < count; i++) enqueue(random, received + random.nextInt(3) - 1);
                } else if (roll < 26 && depth < 3) {
                    depth++;
                    try {
                        queue.drain(received + random.nextInt(2), this);
                    } finally {
                        depth--;
                    }
                }
            };
        }
    }

    private static final class CurrentQueue implements Queue {
        final LatencyTaskQueue queue = new LatencyTaskQueue();

        @Override
        public void add(int transaction, Runnable task) {
            queue.add(transaction, task);
        }

        @Override
        public void addNext(int transaction, Runnable task, Runnable next) {
            queue.addWithNextTransaction(transaction, task, next);
        }

        @Override
        public void drain(int transaction, Driver driver) {
            var node = queue.drainReady(transaction);
            LatencyTaskQueue.TaskNode released = null;
            try {
                while (node != null) {
                    var next = node.next();
                    var task = node.runnable();
                    released = node.clearAndLink(released);
                    task.run();
                    node = next;
                }
            } catch (IllegalStateException aborted) {
                if (driver != null) driver.log.add(Integer.MIN_VALUE);
            } finally {
                queue.recycle(released);
            }
        }
    }

    private static final class ScanQueue implements Queue {
        final ScanLatencyTaskQueue queue = new ScanLatencyTaskQueue();

        @Override
        public void add(int transaction, Runnable task) {
            queue.add(transaction, task);
        }

        @Override
        public void addNext(int transaction, Runnable task, Runnable next) {
            queue.addWithNextTransaction(transaction, task, next);
        }

        @Override
        public void drain(int transaction, Driver driver) {
            LatencyTaskQueueOrderTest.drain(queue, transaction);
        }
    }

    /**
     * The intended order as a list that every drain scans in full. A task goes before the first queued task two or
     * more above it, or at the end when there is none. A drain takes every task at or below the answered transaction
     * in list order, each followed by its continuation when that is ready too; a continuation that is not ready takes
     * its task's place.
     */
    private final class ModelQueue implements Queue {
        private final List<Entry> entries = new ArrayList<>();

        @Override
        public void add(int transaction, Runnable task) {
            insert(new Entry(transaction, task, null));
        }

        @Override
        public void addNext(int transaction, Runnable task, Runnable next) {
            insert(new Entry(transaction, task, next));
        }

        private void insert(Entry entry) {
            int index = 0;
            while (index < entries.size() && entries.get(index).transaction < (long) entry.transaction + 2) index++;
            if (index < entries.size()) lateInserts++;
            entries.add(index, entry);
        }

        @Override
        public void drain(int transaction, Driver driver) {
            List<Runnable> ready = new ArrayList<>();
            boolean waiting = false;
            boolean stopping = false;
            int index = 0;
            while (index < entries.size()) {
                Entry entry = entries.get(index);
                if (entry.transaction > transaction) {
                    waiting = true;
                    if (entry.transaction >= (long) transaction + 2) stopping = true;
                    index++;
                    continue;
                }
                if (waiting) readyBehindWaiting++;
                ready.add(entry.task);
                if (entry.continuation == null) {
                    entries.remove(index);
                    continue;
                }
                int next = entry.transaction + 1;
                if (next <= transaction) {
                    ready.add(entry.continuation);
                    entries.remove(index);
                    readyContinuations++;
                } else {
                    entries.set(index, new Entry(next, entry.continuation, null));
                    deferredContinuations++;
                    index++;
                }
            }
            if (stopping) stoppingDrains++;
            try {
                for (Runnable task : ready) task.run();
            } catch (IllegalStateException aborted) {
                if (driver != null) driver.log.add(Integer.MIN_VALUE);
            }
        }
    }

    private record Entry(int transaction, Runnable task, Runnable continuation) {}
}
