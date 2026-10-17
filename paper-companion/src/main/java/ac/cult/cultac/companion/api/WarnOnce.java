package ac.cult.cultac.companion.api;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

final class WarnOnce {
    private final Logger logger;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    WarnOnce(Logger logger) {
        this.logger = logger;
    }

    void warn(String key, String message) {
        if (warned.add(key)) logger.warning(message);
    }
}
