package ac.cult.cultac.cloud.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    @Test
    void failedConnectsDoubleUpToTheCap() {
        var backoff = new CloudConnection.Backoff(MIN, MAX, STABLE);
        long[] seconds = {1, 2, 4, 8, 16, 30, 30};
        for (long expected : seconds) assertEquals(Duration.ofSeconds(expected), backoff.failed());
    }

    @Test
    void aSocketClosedSoonAfterOpeningWaitsAndKeepsDoubling() {
        var backoff = new CloudConnection.Backoff(MIN, MAX, STABLE);
        long quick = Duration.ofMillis(200).toNanos();
        long[] seconds = {1, 2, 4, 8, 16, 30, 30};
        for (long expected : seconds) assertEquals(Duration.ofSeconds(expected), backoff.closed(quick));
    }

    @Test
    void aStableSocketReconnectsAtOnceAndStartsTheWaitOver() {
        var backoff = new CloudConnection.Backoff(MIN, MAX, STABLE);
        for (int i = 0; i < 6; i++) backoff.failed();
        assertEquals(Duration.ZERO, backoff.closed(STABLE));
        assertEquals(MIN, backoff.failed());
        assertEquals(Duration.ofSeconds(2), backoff.closed(STABLE - 1));
    }
}
