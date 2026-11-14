package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.bedrock.player.BedrockPlayerState;
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

    /**
     * The auth tick the client answered {@code markerId} after. That tick reached the wire with an input {@code state}
     * may not have accepted, so it dates the marker only for a client whose later inputs follow it.
     */
    long release(long markerId, BedrockPlayerState state) {
        long tick = release(markerId, state.processedClientTick());
        state.answeredAfterMovementTick(tick);
        return tick;
    }

    long release(long markerId, long fallbackTick) {
        Long tick = pending.remove(markerId);
        return tick == null || tick == NO_ACK ? fallbackTick : tick;
    }

    void clear() {
        pending.clear();
    }
}
