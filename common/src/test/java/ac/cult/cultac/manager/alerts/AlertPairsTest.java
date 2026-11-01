package ac.cult.cultac.manager.alerts;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AlertPairsTest {
    private final AtomicLong clock = new AtomicLong(1_793_140_000_000L);
    private final AlertPairs pairs = new AlertPairs(null, clock::get);

    @Test
    void eachPlayerTakesOneMessageOfAPair() {
        Object staff = new Object();
        Object other = new Object();
        assertTrue(pairs.take("p", staff));
        assertFalse(pairs.take("p", staff));
        assertTrue(pairs.take("p", other));
        assertTrue(pairs.take("q", staff));
    }

    @Test
    void aPairIsForgottenAfterItsWindow() {
        Object staff = new Object();
        assertTrue(pairs.take("p", staff));
        clock.addAndGet(AlertPairs.WINDOW_MILLIS);
        assertFalse(pairs.take("p", staff));
        clock.addAndGet(1);
        assertTrue(pairs.take("p", staff));
    }
}
