package ac.cult.cultac.cloud.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class QueuedBytesTest {
    @Test
    void twoSocketsCannotExceedTheProcessCap() {
        AtomicLong process = new AtomicLong();
        QueuedBytes a = new QueuedBytes(process);
        QueuedBytes b = new QueuedBytes(process);
        assertTrue(a.tryAdd(60, 64, 100));
        assertFalse(b.tryAdd(50, 64, 100));
        assertEquals(0, b.get());
        assertEquals(60, process.get());
        assertTrue(b.tryAdd(40, 64, 100));
        assertFalse(a.tryAdd(5, 64, 100));
        assertEquals(60, a.get());
        assertEquals(100, process.get());
    }

    @Test
    void aSocketCannotExceedItsOwnCap() {
        QueuedBytes a = new QueuedBytes(new AtomicLong());
        assertFalse(a.tryAdd(65, 64, 100));
        assertEquals(0, a.get());
        assertEquals(0, a.process());
    }

    @Test
    void releasesGiveBackToBothCounts() {
        AtomicLong process = new AtomicLong();
        QueuedBytes a = new QueuedBytes(process);
        assertTrue(a.tryAdd(30, 64, 100));
        a.addAndGet(-30);
        assertEquals(0, a.get());
        assertEquals(0, process.get());
    }
}
