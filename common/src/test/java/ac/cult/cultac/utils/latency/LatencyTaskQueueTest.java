package ac.cult.cultac.utils.latency;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class LatencyTaskQueueTest {
    @Test public void nativeSpawnAndAttributesCannotBeHiddenBehindFutureJavaTasks() {
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
        assertEquals(List.of("java spawn", "bedrock spawn", "jump 0.5",
                "jump 0.67052144", "future"), applied);
    }

    @Test public void outOfOrderDependenciesPreserveContinuationsAndFifo() {
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

    private static void drain(LatencyTaskQueue queue, int transaction) {
        var node = queue.drainReady(transaction);
        while (node != null) {
            var next = node.next();
            var task = node.runnable();
            node.clear();
            task.run();
            node = next;
        }
    }
}
