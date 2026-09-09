package ac.cult.cultac.bedrock.logging;

import com.google.common.collect.EvictingQueue;
import java.util.Locale;

/** Bounded, one-shot requests; no player/session objects are retained here. */
public final class PendingPacketCaptures {
    private final EvictingQueue<String> names = EvictingQueue.create(8);

    public synchronized String arm(String username) {
        String name = normalize(username);
        names.remove(name);
        String evicted = names.remainingCapacity() == 0 ? names.peek() : null;
        names.add(name);
        return evicted;
    }

    public synchronized boolean consume(String username) {
        return names.remove(normalize(username));
    }

    public synchronized void clear() {
        names.clear();
    }

    private static String normalize(String username) {
        return username.toLowerCase(Locale.ROOT);
    }
}
