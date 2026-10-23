package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.utils.anticheat.LogThrottle;
import ac.cult.cultac.utils.anticheat.LogUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReportFailureThrottleTest {
    private static final String FLOOD = "packet handler test:flood";
    private static final String OTHER = "inbound packet decoding";

    private final long[] now = {0};

    @BeforeEach
    void throttleOnAFakeClock() {
        OfflineCultTestBootstrap.installConfig();
        CultNetworkManager.failureThrottle(new LogThrottle(10, () -> now[0]));
    }

    @AfterEach
    void restoreThrottle() {
        CultNetworkManager.failureThrottle(new LogThrottle());
    }

    @Test
    void aFloodOfOneOperationLogsOneTraceThenACount() {
        try (var sink = new SevereSink()) {
            for (int i = 0; i < 100; i++)
                CultNetworkManager.reportFailure(FLOOD, new IllegalStateException("bad " + i));
            assertEquals(1, sink.records.size(), sink.records.toString());
            String first = sink.records.get(0);
            assertTrue(first.startsWith("CultAC failure in " + FLOOD), first);
            assertTrue(first.contains("IllegalStateException: bad 0"), first);
            assertTrue(first.contains("\tat "), "The first occurrence carries its stack trace: " + first);

            now[0] = 20;
            CultNetworkManager.reportFailure(FLOOD, new IllegalStateException("late"));
            assertEquals(2, sink.records.size(), sink.records.toString());
            String repeated = sink.records.get(1);
            assertTrue(repeated.startsWith("CultAC failure in " + FLOOD), repeated);
            assertTrue(repeated.contains("IllegalStateException: late"), repeated);
            assertTrue(repeated.contains("99 more since the last report"), repeated);
            assertFalse(repeated.contains("\n"), repeated);
        }
    }

    @Test
    void anotherOperationStillLogsItsFirstTrace() {
        try (var sink = new SevereSink()) {
            for (int i = 0; i < 50; i++) CultNetworkManager.reportFailure(FLOOD, new IllegalStateException("bad"));
            for (int i = 0; i < 50; i++) CultNetworkManager.reportFailure(OTHER, new IllegalArgumentException("frame"));
            assertEquals(2, sink.records.size(), sink.records.toString());
            String other = sink.records.get(1);
            assertTrue(other.startsWith("CultAC failure in " + OTHER), other);
            assertTrue(other.contains("IllegalArgumentException: frame"), other);
            assertTrue(other.contains("\tat "), other);
        }
    }

    @Test
    void subjectsShareTheirOperationsThrottle() {
        try (var sink = new SevereSink()) {
            for (int i = 0; i < 300; i++)
                CultNetworkManager.reportFailure("registry generation", "Player" + i, new IllegalStateException());
            assertEquals(1, sink.records.size(), sink.records.toString());
            String first = sink.records.get(0);
            assertTrue(first.startsWith("CultAC failure in registry generation for Player0"), first);
        }
    }

    private static final class SevereSink extends Handler implements AutoCloseable {
        private final Logger logger = LogUtil.getLogger();
        final List<String> records = new ArrayList<>();

        SevereSink() {
            logger.addHandler(this);
        }

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel() == Level.SEVERE) records.add(record.getMessage());
        }

        @Override
        public void flush() {}

        @Override
        public void close() {
            logger.removeHandler(this);
        }
    }
}
