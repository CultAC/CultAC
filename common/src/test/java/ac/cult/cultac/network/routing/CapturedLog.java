package ac.cult.cultac.network.routing;

import ac.cult.cultac.utils.anticheat.LogUtil;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Records the plugin log's lines at one level that start with a prefix, until closed. */
final class CapturedLog extends Handler implements AutoCloseable {
    private final Logger logger = LogUtil.getLogger();
    private final Level level;
    private final String prefix;
    private final List<String> messages = new CopyOnWriteArrayList<>();

    CapturedLog(Level level, String prefix) {
        this.level = level;
        this.prefix = prefix;
        logger.addHandler(this);
    }

    @Override
    public void publish(LogRecord record) {
        if (record.getLevel() == level && record.getMessage().startsWith(prefix)) messages.add(record.getMessage());
    }

    List<String> messages() {
        return List.copyOf(messages);
    }

    @Override
    public void flush() {}

    @Override
    public void close() {
        logger.removeHandler(this);
    }
}
