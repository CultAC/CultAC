package ac.cult.cultac.cloud;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PaceTest {
    private final AtomicLong clock = new AtomicLong();

    @Test
    void eachKeyTakesUpToTheMostInAWindow() {
        Pace<String> pace = new Pace<>(1_000, 2, clock::get);
        assertNotNull(pace.take("a"));
        assertNotNull(pace.take("a"));
        assertNull(pace.take("a"));
        assertNotNull(pace.take("b"));
    }

    @Test
    void theWindowStartsOverAfterItsLength() {
        Pace<String> pace = new Pace<>(1_000, 1, clock::get);
        assertNotNull(pace.take("a"));
        clock.set(999);
        assertNull(pace.take("a"));
        clock.set(1_000);
        assertNotNull(pace.take("a"));
        clock.set(1_999);
        assertNull(pace.take("a"));
    }

    @Test
    void aTakeGivenBackCountsAgainOnce() {
        Pace<String> pace = new Pace<>(1_000, 1, clock::get);
        Runnable taken = pace.take("a");
        assertNotNull(taken);
        taken.run();
        assertNotNull(pace.take("a"));
        taken.run();
        assertNull(pace.take("a"));
    }

    @Test
    void aTakeGivenBackAfterItsWindowFreesNothingInTheNext() {
        Pace<String> pace = new Pace<>(1_000, 1, clock::get);
        Runnable old = pace.take("a");
        assertNotNull(old);
        clock.set(1_000);
        assertNotNull(pace.take("a"));
        old.run();
        assertNull(pace.take("a"));
    }

    @Test
    void theMostAppliesFromTheNextTake() {
        Pace<String> pace = new Pace<>(1_000, 1, clock::get);
        assertNotNull(pace.take("a"));
        pace.max(2);
        assertNotNull(pace.take("a"));
        assertNull(pace.take("a"));
        pace.max(1);
        assertNotNull(pace.take("b"));
        assertNull(pace.take("b"));
    }

    @Test
    void aClockStartingBelowZeroPacesTheSame() {
        clock.set(Long.MIN_VALUE / 2);
        Pace<String> pace = new Pace<>(1_000, 1, clock::get);
        assertNotNull(pace.take("a"));
        assertNull(pace.take("a"));
        clock.addAndGet(1_000);
        assertNotNull(pace.take("a"));
    }

    @Test
    void theSystemClockPaces() {
        Pace<String> pace = new Pace<>(60_000, 1);
        assertNotNull(pace.take("a"));
        assertNull(pace.take("a"));
    }
}
