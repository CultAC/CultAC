package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.utils.anticheat.LogUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Records {@link CultNetworkManager#reportFailure} calls. A forwarded frame looks the same whether a value was
 * declined or its decoding failed; only the report tells them apart.
 */
final class ReportedFailures extends Handler implements AutoCloseable {
    private final Logger logger = LogUtil.getLogger();
    private final List<String> messages = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    ReportedFailures() {
        logger.addHandler(this);
    }

    @Override
    public void publish(LogRecord record) {
        if (record.getLevel() == Level.SEVERE && record.getMessage().startsWith("CultAC failure"))
            messages.add(record.getMessage().lines().findFirst().orElse(""));
        else if (record.getLevel() == Level.WARNING && record.getMessage().startsWith("CultAC paused checks"))
            warnings.add(record.getMessage());
    }

    /** Paused-check warnings; each is one line, without a stack trace. */
    List<String> warnings() {
        return List.copyOf(warnings);
    }

    List<String> messages() {
        return List.copyOf(messages);
    }

    void assertNone() {
        assertEquals(List.of(), messages());
    }

    @Override
    public void flush() {}

    @Override
    public void close() {
        logger.removeHandler(this);
    }
}
