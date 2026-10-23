package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Thread CPU per queue operation of LatencyTaskQueue against the single-list scan, on a FairReach-like mix: per
 * transaction a next-transaction task and a movement task at its proof (out of insertion order), extra movement tasks,
 * a third of the movement tasks with a continuation, and pongs a fixed number of transactions behind. Runs only with
 * CULT_ENTITY_BENCH set.
 */
class LatencyTaskQueueCpuBenchTest {
    private static final int[] PENDING_TARGETS = {9, 16, 40, 90, 200};
    private static final int TRANSACTIONS = 400_000;
    private static final int REPS = 11;
    // Thread CPU time ticks every 15.6 ms on Windows, so one sample spans several passes.
    private static final int PASSES = 12;
    private static final double PAIR_CHANCE = 0.87;
    private static final double EXTRA_CHANCE = 0.71;
    private static final double CONTINUATION_CHANCE = 0.3;
    private static final int ADD = 0;
    private static final int ADD_NEXT = 1;
    private static final int DRAIN = 2;

    private static long sink;
    private static final Runnable TASK = () -> sink++;
    private static final Runnable PARENT = () -> sink += 2;
    private static final Runnable CONTINUATION = () -> sink += 3;

    private int[] kinds;
    private int[] transactions;
    private int opCount;

    @Test
    void fairReachMix() {
        String out = System.getenv("CULT_ENTITY_BENCH");
        assumeTrue(out != null && !out.isEmpty());
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        StringBuilder report = new StringBuilder();
        for (int target : PENDING_TARGETS) {
            int inFlight = 1;
            script(inFlight, 20_000, 42);
            while (meanPending() < target) script(++inFlight, 20_000, 42);
            script(inFlight, TRANSACTIONS, 42 + inFlight);
            double pending = meanPending();
            for (int warmup = 0; warmup < 5; warmup++) {
                runScan();
                runRuns();
            }
            long[] scan = new long[REPS];
            long[] runs = new long[REPS];
            for (int rep = 0; rep < REPS; rep++) {
                boolean scanFirst = (rep & 1) == 0;
                for (int side = 0; side < 2; side++) {
                    boolean scanSide = (side == 0) == scanFirst;
                    long begin = bean.getCurrentThreadCpuTime();
                    for (int pass = 0; pass < PASSES; pass++) {
                        if (scanSide) runScan();
                        else runRuns();
                    }
                    long elapsed = (bean.getCurrentThreadCpuTime() - begin) / PASSES;
                    if (scanSide) scan[rep] = elapsed;
                    else runs[rep] = elapsed;
                }
            }
            Arrays.sort(scan);
            Arrays.sort(runs);
            double scanOp = (double) scan[REPS / 2] / opCount;
            double runsOp = (double) runs[REPS / 2] / opCount;
            report.append(String.format(
                    "pending=%.1f inFlight=%d scan %.1f ns/op [%.1f..%.1f] runs %.1f ns/op [%.1f..%.1f] runs/scan=%.2f%n",
                    pending,
                    inFlight,
                    scanOp,
                    (double) scan[0] / opCount,
                    (double) scan[REPS - 1] / opCount,
                    runsOp,
                    (double) runs[0] / opCount,
                    (double) runs[REPS - 1] / opCount,
                    runsOp / scanOp));
        }
        System.out.print(report);
    }

    private void script(int inFlight, int transactionCount, long seed) {
        Random random = new Random(seed);
        kinds = new int[transactionCount * 6];
        transactions = new int[transactionCount * 6];
        int n = 0;
        int sent = 0;
        for (int t = 0; t < transactionCount; t++) {
            sent++;
            if (random.nextDouble() < PAIR_CHANCE) {
                kinds[n] = ADD;
                transactions[n++] = sent + 1;
                kinds[n] = random.nextDouble() < CONTINUATION_CHANCE ? ADD_NEXT : ADD;
                transactions[n++] = sent;
            }
            if (random.nextDouble() < EXTRA_CHANCE) {
                kinds[n] = random.nextDouble() < CONTINUATION_CHANCE ? ADD_NEXT : ADD;
                transactions[n++] = sent;
            }
            int ack = sent - inFlight;
            if (ack > 0) {
                kinds[n] = DRAIN;
                transactions[n++] = ack;
            }
        }
        kinds[n] = DRAIN;
        transactions[n++] = Integer.MAX_VALUE;
        opCount = n;
    }

    /** The mean number of queued tasks, continuations not yet created excluded, when a drain begins. */
    private double meanPending() {
        ScanLatencyTaskQueue queue = new ScanLatencyTaskQueue();
        long pendingSum = 0;
        long drains = 0;
        int pending = 0;
        for (int i = 0; i < opCount; i++) {
            if (kinds[i] == ADD) {
                queue.add(transactions[i], TASK);
                pending++;
            } else if (kinds[i] == ADD_NEXT) {
                queue.addWithNextTransaction(transactions[i], PARENT, CONTINUATION);
                pending++;
            } else {
                pendingSum += pending;
                drains++;
                ScanLatencyTaskQueue.TaskNode node = queue.drainReady(transactions[i]);
                ScanLatencyTaskQueue.TaskNode released = null;
                while (node != null) {
                    ScanLatencyTaskQueue.TaskNode next = node.next();
                    Runnable task = node.runnable();
                    released = node.clearAndLink(released);
                    task.run();
                    if (task != PARENT) pending--;
                    node = next;
                }
                queue.recycle(released);
            }
        }
        return (double) pendingSum / drains;
    }

    private void runScan() {
        ScanLatencyTaskQueue queue = new ScanLatencyTaskQueue();
        for (int i = 0; i < opCount; i++) {
            if (kinds[i] == ADD) {
                queue.add(transactions[i], TASK);
            } else if (kinds[i] == ADD_NEXT) {
                queue.addWithNextTransaction(transactions[i], PARENT, CONTINUATION);
            } else {
                ScanLatencyTaskQueue.TaskNode node = queue.drainReady(transactions[i]);
                ScanLatencyTaskQueue.TaskNode released = null;
                while (node != null) {
                    ScanLatencyTaskQueue.TaskNode next = node.next();
                    Runnable task = node.runnable();
                    released = node.clearAndLink(released);
                    task.run();
                    node = next;
                }
                queue.recycle(released);
            }
        }
    }

    private void runRuns() {
        LatencyTaskQueue queue = new LatencyTaskQueue();
        for (int i = 0; i < opCount; i++) {
            if (kinds[i] == ADD) {
                queue.add(transactions[i], TASK);
            } else if (kinds[i] == ADD_NEXT) {
                queue.addWithNextTransaction(transactions[i], PARENT, CONTINUATION);
            } else {
                LatencyTaskQueue.TaskNode node = queue.drainReady(transactions[i]);
                LatencyTaskQueue.TaskNode released = null;
                while (node != null) {
                    LatencyTaskQueue.TaskNode next = node.next();
                    Runnable task = node.runnable();
                    released = node.clearAndLink(released);
                    task.run();
                    node = next;
                }
                queue.recycle(released);
            }
        }
    }
}
