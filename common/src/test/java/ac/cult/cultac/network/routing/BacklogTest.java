package ac.cult.cultac.network.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.utils.anticheat.LogThrottle;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BacklogTest {
    private final AtomicLong nanos = new AtomicLong();
    private final Backlog backlog =
            new Backlog("Dropped a probe", 2, 100, new LogThrottle(TimeUnit.MINUTES.toNanos(1), nanos::get));

    @BeforeAll
    static void logging() {
        OfflineCultTestBootstrap.installConfig();
    }

    @Test
    void holdsAtMostItsItemsAndBytes() {
        assertTrue(backlog.admit(40));
        assertTrue(backlog.admit(40));
        assertFalse(backlog.admit(1));
        backlog.release(40);
        assertFalse(backlog.admit(61));
        assertTrue(backlog.admit(60));
        assertFalse(backlog.admit(0));
        backlog.release(40);
        backlog.release(60);
        assertTrue(backlog.admit(100));
        assertFalse(backlog.admit(1));
    }

    @Test
    void dropsAreLoggedAtMostOnceAMinuteWithTheirCount() {
        try (CapturedLog log = new CapturedLog(Level.WARNING, "Dropped a probe")) {
            assertTrue(backlog.admit(100));
            assertFalse(backlog.admit(1));
            assertFalse(backlog.admit(1));
            assertFalse(backlog.admit(1));
            assertEquals(List.of("Dropped a probe"), log.messages());

            nanos.addAndGet(TimeUnit.SECONDS.toNanos(59));
            assertFalse(backlog.admit(1));
            assertEquals(1, log.messages().size());

            nanos.addAndGet(TimeUnit.SECONDS.toNanos(1));
            assertFalse(backlog.admit(1));
            assertEquals(List.of("Dropped a probe", "Dropped a probe (3 more since the last report)"), log.messages());
        }
    }
}
