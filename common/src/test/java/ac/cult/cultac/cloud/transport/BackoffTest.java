package ac.cult.cultac.cloud.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * A socket the service closes soon after it opens (another server took the same server id, a connect limit, a revoked
 * key) waits like a failed connect, so a misconfigured fleet cannot reconnect in a tight loop.
 */
class BackoffTest {
    private static final Duration MIN = Duration.ofSeconds(1);
    private static final Duration MAX = Duration.ofSeconds(30);
    private static final long STABLE = Duration.ofSeconds(30).toNanos();
    private static final Duration SPREAD = Duration.ofSeconds(5);

    @Test
    void failedConnectsDoubleUpToTheCap() {
        var backoff = new CloudConnection.Backoff(MIN, MAX, STABLE, SPREAD, () -> 0);
        long[] seconds = {1, 2, 4, 8, 16, 30, 30};
        for (long expected : seconds) assertEquals(Duration.ofSeconds(expected), backoff.failed());
    }

    @Test
    void aSocketClosedSoonAfterOpeningWaitsAndKeepsDoubling() {
        var backoff = new CloudConnection.Backoff(MIN, MAX, STABLE, SPREAD, () -> 0);
        long quick = Duration.ofMillis(200).toNanos();
        long[] seconds = {1, 2, 4, 8, 16, 30, 30};
        for (long expected : seconds) assertEquals(Duration.ofSeconds(expected), backoff.closed(quick));
    }

    @Test
    void aStableSocketReconnectsAtOnceAndStartsTheWaitOver() {
        var backoff = new CloudConnection.Backoff(MIN, MAX, STABLE, SPREAD, () -> 0);
        for (int i = 0; i < 6; i++) backoff.failed();
        assertEquals(Duration.ZERO, backoff.closed(STABLE));
        assertEquals(MIN, backoff.failed());
        assertEquals(Duration.ofSeconds(2), backoff.closed(STABLE - 1));
    }

    @Test
    void eachStepGainsARandomPartUpToItselfAndAtMostTheSpread() {
        var backoff = new CloudConnection.Backoff(MIN, MAX, STABLE, SPREAD, () -> 0.5);
        long[] millis = {1500, 3000, 6000, 10_500, 18_500, 32_500, 32_500};
        for (long expected : millis) assertEquals(Duration.ofMillis(expected), backoff.failed());
    }

    @Test
    void aStableSocketReconnectsWithinTheSpread() {
        var backoff = new CloudConnection.Backoff(MIN, MAX, STABLE, SPREAD, () -> 0.999);
        for (int i = 0; i < 6; i++) backoff.failed();
        Duration wait = backoff.closed(STABLE);
        assertTrue(wait.compareTo(Duration.ofMillis(4900)) > 0 && wait.compareTo(SPREAD) < 0, wait.toString());
        assertEquals(Duration.ofMillis(1999), backoff.failed());
    }
}
