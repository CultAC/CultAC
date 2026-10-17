package ac.cult.cultac.utils.latency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class LatencyTaskQueueTest {
    @Test
    public void nativeSpawnAndAttributesCannotBeHiddenBehindFutureJavaTasks() {
        var queue = new LatencyTaskQueue();
        var applied = new ArrayList<String>();
        queue.add(23, () -> applied.add("java spawn"));
        queue.add(100, () -> applied.add("future"));
        // Native receipt precedes the trailing Java spawn proof. Its callbacks
        // join the queue after later Java packets have already been observed.
        queue.add(23, () -> applied.add("bedrock spawn"));
        queue.add(23, () -> applied.add("jump 0.5"));
        drain(queue, 22);
        assertEquals(List.of(), applied);
        drain(queue, 23);
        assertEquals(List.of("java spawn", "bedrock spawn", "jump 0.5"), applied);
        // The next native receipt can now apply the complete attribute packet.
        applied.add("jump 0.67052144");
        drain(queue, 100);
        assertEquals(List.of("java spawn", "bedrock spawn", "jump 0.5", "jump 0.67052144", "future"), applied);
    }

    @Test
    public void outOfOrderDependenciesPreserveContinuationsAndFifo() {
        var queue = new LatencyTaskQueue();
        var applied = new ArrayList<Integer>();
        queue.addWithNextTransaction(10, () -> applied.add(10), () -> applied.add(11));
        queue.add(50, () -> applied.add(50));
        queue.addWithNextTransaction(10, () -> applied.add(12), () -> applied.add(13));
        drain(queue, 10);
        assertEquals(List.of(10, 12), applied);
        drain(queue, 11);
        assertEquals(List.of(10, 12, 11, 13), applied);
        queue.add(12, () -> applied.add(14));
        drain(queue, 12);
        drain(queue, 50);
        assertEquals(List.of(10, 12, 11, 13, 14, 50), applied);
    }

    @Test
    public void nestedDrainKeepsOuterBatchOrder() {
        var queue = new LatencyTaskQueue();
        var applied = new ArrayList<Integer>();
        for (int round = 0; round < 10; round++) {
            queue.add(1000, () -> applied.add(9));
            queue.addWithNextTransaction(
                    10,
                    () -> {
                        applied.add(1);
                        queue.add(10, () -> applied.add(3));
                        drain(queue, 10);
                    },
                    () -> applied.add(2));
            queue.add(10, () -> applied.add(4));
            drain(queue, 10);
            drain(queue, 11);
            drain(queue, 1000);
            assertEquals(List.of(1, 3, 4, 2, 9), applied);
            applied.clear();
        }
    }

    @Test
    public void recycledNodeRunsItsContinuation() {
        var queue = new LatencyTaskQueue();
        Runnable task = () -> {};
        queue.addWithNextTransaction(5, task, task);
        drain(queue, 6);
        var applied = new ArrayList<Integer>();
        queue.addWithNextTransaction(7, () -> applied.add(1), () -> applied.add(2));
        drain(queue, 8);
        assertEquals(List.of(1, 2), applied);
    }

    @Test
    public void abortedDrainKeepsContinuations() {
        var queue = new LatencyTaskQueue();
        var applied = new ArrayList<Integer>();
        queue.addWithNextTransaction(
                1,
                () -> {
                    throw new AssertionError("abort");
                },
                () -> applied.add(2));
        queue.add(1, () -> applied.add(-1));
        queue.add(100, () -> applied.add(100));
        try {
            drain(queue, 1);
            throw new AssertionError("Expected callback abort");
        } catch (AssertionError failure) {
            assertEquals("abort", failure.getMessage());
        }
        drain(queue, 2);
        queue.add(3, () -> applied.add(3));
        drain(queue, 3);
        drain(queue, 100);
        assertEquals(List.of(2, 3, 100), applied);
    }

    @Test
    public void concurrentEnqueuesRunEveryTaskOnce() throws Exception {
        var queue = new LatencyTaskQueue();
        var start = new CountDownLatch(1);
        var finished = new AtomicInteger();
        boolean[] applied = new boolean[2000];
        Thread[] producers = new Thread[4];
        for (int index = 0; index < producers.length; index++) {
            int base = index * 500;
            producers[index] = new Thread(() -> {
                try {
                    start.await();
                    for (int offset = 0; offset < 500; offset++) {
                        int id = base + offset;
                        queue.add(10, () -> {
                            assertFalse(applied[id]);
                            applied[id] = true;
                        });
                    }
                } catch (InterruptedException failure) {
                    throw new AssertionError(failure);
                } finally {
                    finished.incrementAndGet();
                }
            });
            producers[index].start();
        }
        start.countDown();
        while (finished.get() < producers.length) {
            drain(queue, 10);
            Thread.yield();
        }
        for (Thread producer : producers) producer.join();
        drain(queue, 10);
        for (boolean ran : applied) assertTrue(ran);
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
}
