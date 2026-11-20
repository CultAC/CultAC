package ac.cult.cultac.bedrock.bridge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Captures the last auth tick in wire order when a movement effect's latency marker returns. */
final class GeyserMovementEffectAcks {
    private static final long NO_ACK = Long.MIN_VALUE;
    private final Map<Long, Long> pending = new ConcurrentHashMap<>();
    private volatile long lastAuthTick;

    void auth(long tick) {
        lastAuthTick = tick;
    }

    void watch(long markerId) {
        pending.put(markerId, NO_ACK);
    }

    void latency(long wireTimestamp) {
        // Bedrock returns server latency timestamps in microseconds.
        if (wireTimestamp % 1_000_000L != 0) return;
        pending.computeIfPresent(wireTimestamp / 1_000_000L, (id, previous) -> lastAuthTick);
    }

    long release(long markerId, long fallbackTick) {
        Long tick = pending.remove(markerId);
        return tick == null || tick == NO_ACK ? fallbackTick : tick;
    }

    void clear() {
        pending.clear();
    }
}
