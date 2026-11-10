package ac.cult.cultac.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LeasedJobsTest {
    @Test
    void aPollWaitMovesByUpToAFifthEitherWay() {
        assertEquals(8, LeasedJobs.spread(10, 0));
        assertEquals(10, LeasedJobs.spread(10, 0.5));
        assertEquals(12, LeasedJobs.spread(10, 0.999));
        assertEquals(240, LeasedJobs.spread(300, 0));
        assertEquals(360, LeasedJobs.spread(300, 0.999));
    }

    @Test
    void aPollWaitIsNeverUnderASecond() {
        assertEquals(1, LeasedJobs.spread(1, 0));
        assertEquals(1, LeasedJobs.spread(0, 0.5));
    }

    @Test
    void aResultIsOwedUntilPaidOrForADayAtMost() {
        AtomicLong nanos = new AtomicLong();
        LeasedJobs.Handled handled = new LeasedJobs.Handled(10, nanos::get);
        handled.owe("a");
        handled.owe("b");
        handled.paid("b");
        assertFalse(handled.owing("b"));
        nanos.set(LeasedJobs.MAX_OWED.toNanos());
        handled.owe("a");
        assertTrue(handled.owing("a"));
        assertTrue(handled.contains("a"));
        nanos.incrementAndGet();
        assertFalse(handled.owing("a"));
        assertFalse(handled.contains("a"));
    }

    @Test
    void onlyTheMostRecentResultsStayOwed() {
        LeasedJobs.Handled handled = new LeasedJobs.Handled(2, () -> 0);
        handled.owe("a");
        handled.owe("b");
        handled.owe("c");
        assertFalse(handled.owing("a"));
        assertTrue(handled.owing("b"));
        assertTrue(handled.owing("c"));
    }
}
