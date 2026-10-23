package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * LatencyTaskQueue runs tasks in the order of the single-list scan it replaces. Random scripts drive both: tasks at
 * past, present, future and extreme transactions, continuations, drains that skip, repeat or go backwards, tasks that
 * enqueue or drain again while their batch runs, and tasks that throw part way through a batch. The ids run, in
 * order, must match after every drain.
 */
class LatencyTaskQueueOrderTest {
    private static final Field RUN_FIRST = field(LatencyTaskQueue.class, "runFirst");
    private static final Field FIRST_TASK = field(LatencyTaskQueue.class, "firstTask");
    private static final Field TRANSACTION = field(LatencyTaskQueue.TaskNode.class, "transaction");
    private int runsBeyondFirst;
    private int overflowUsed;
    private int firstRunOnlyDrains;
    private int threeRunDrains;

    @Test
    void tasksRunInTheScanOrder() throws Exception {
        for (long seed = 0; seed < 600; seed++) compare(seed, 2_500);
        assertTrue(runsBeyondFirst > 0, "no task ever needed a second run");
        assertTrue(overflowUsed > 0, "no task ever went to the overflow");
        assertTrue(firstRunOnlyDrains > 0, "no drain found ready tasks in the first run alone");
        assertTrue(threeRunDrains > 0, "no drain found ready tasks in three runs");
    }

    @Test
    void movementLikeTrafficRunsInTheScanOrder() throws Exception {
        for (long seed = 0; seed < 200; seed++) traffic(seed, 4_000);
        assertTrue(firstRunOnlyDrains > 0, "no drain found ready tasks in the first run alone");
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    private void observeDrain(LatencyTaskQueue queue, int transaction) {
        try {
            Object[] first = (Object[]) RUN_FIRST.get(queue);
            boolean othersEmpty = FIRST_TASK.get(queue) == null;
            int readyRuns = 0;
            for (int run = 0; run < first.length; run++) {
                if (first[run] == null) continue;
                if (run > 0) othersEmpty = false;
                if ((int) TRANSACTION.get(first[run]) <= transaction) readyRuns++;
            }
            if (othersEmpty && readyRuns == 1) firstRunOnlyDrains++;
            if (readyRuns >= 3) threeRunDrains++;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private void compare(long seed, int steps) throws Exception {
        Random random = new Random(seed);
        Driver legacy = new Driver(new LegacyQueue(), seed);
        CurrentQueue current = new CurrentQueue();
        Driver candidate = new Driver(current, seed);
        for (int step = 0; step < steps; step++) {
            long op = random.nextLong();
            legacy.apply(op);
            candidate.apply(op);
            observe(current.queue);
            assertEquals(legacy.log, candidate.log, "seed=" + seed + " step=" + step);
        }
        candidate.queue.drain(Integer.MAX_VALUE, candidate);
        legacy.queue.drain(Integer.MAX_VALUE, legacy);
        assertEquals(legacy.log, candidate.log, "seed=" + seed + " final");
    }

    /** Pongs in order, movement tasks at the next transaction and a later proof, one task in three at T+1 late. */
    private void traffic(long seed, int steps) {
        List<List<Integer>> logs = new ArrayList<>();
        for (Queue queue : List.of(new LegacyQueue(), new CurrentQueue())) {
            Random script = new Random(seed);
            List<Integer> log = new ArrayList<>();
            int sent = 0;
            int received = 0;
            int id = 0;
            for (int step = 0; step < steps; step++) {
                int kind = script.nextInt(10);
                if (kind < 3) {
                    sent++;
                } else if (kind < 7) {
                    int task = id++;
                    int at = sent + 1 + script.nextInt(2);
                    if (script.nextInt(3) == 0) queue.add(sent + 1, () -> log.add(task));
                    else if (script.nextBoolean()) queue.addNext(at, () -> log.add(task), () -> log.add(-task));
                    else queue.add(at, () -> log.add(task));
                } else if (received < sent) {
                    received += 1 + script.nextInt(Math.min(3, sent - received));
                    queue.drain(received, null);
                }
            }
            queue.drain(Integer.MAX_VALUE, null);
            logs.add(log);
        }
        assertEquals(logs.get(0), logs.get(1), "seed=" + seed);
    }

    private void observe(LatencyTaskQueue queue) throws Exception {
        Object[] first = (Object[]) RUN_FIRST.get(queue);
        for (int run = 1; run < first.length; run++) if (first[run] != null) runsBeyondFirst++;
        if (FIRST_TASK.get(queue) != null) overflowUsed++;
    }

    private interface Queue {
        void add(int transaction, Runnable task);

        void addNext(int transaction, Runnable task, Runnable next);

        /** Drains and runs the batch as LatencyUtils does; a throwing task ends the batch, the rest is dropped. */
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

    private final class CurrentQueue implements Queue {
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
            observeDrain(queue, transaction);
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

    private static final class LegacyQueue implements Queue {
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
            } catch (IllegalStateException aborted) {
                if (driver != null) driver.log.add(Integer.MIN_VALUE);
            } finally {
                queue.recycle(released);
            }
        }
    }
}
