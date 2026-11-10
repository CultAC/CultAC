package ac.cult.cultac.manager.alerts;

import ac.cult.cultac.platform.api.player.PlatformPlayer;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Which subscribers lately held what each kind of message needs, so delivery asks the platform about a subscriber at
 * most once every {@value #RECHECK_MILLIS} ms while the answer stays yes. A permission taken away therefore stops
 * messages within that time. Any thread.
 */
public final class AlertPermits {
    public static final long RECHECK_MILLIS = 60_000;

    private final LongSupplier clock;
    /** Per kind, native player to when its last yes expires. */
    private final Map<AlertKind, Map<Object, Long>> heldUntil = new EnumMap<>(AlertKind.class);

    public AlertPermits(LongSupplier clock) {
        this.clock = clock;
        for (AlertKind kind : AlertKind.values()) heldUntil.put(kind, new ConcurrentHashMap<>());
    }

    /** Whether {@code player} may get a message of {@code kind}; {@code holds} answers once the last yes has expired. */
    public boolean permitted(AlertKind kind, PlatformPlayer player, Predicate<PlatformPlayer> holds) {
        Map<Object, Long> held = heldUntil.get(kind);
        Object handle = player.getNative();
        if (handle == null) return holds.test(player);
        long now = clock.getAsLong();
        Long until = held.get(handle);
        if (until != null && now < until) return true;
        if (!holds.test(player)) {
            held.remove(handle);
            return false;
        }
        held.put(handle, now + RECHECK_MILLIS);
        return true;
    }

    /** Forgets every answer about {@code player}. */
    public void forget(PlatformPlayer player) {
        Object handle = player.getNative();
        if (handle == null) return;
        for (Map<Object, Long> held : heldUntil.values()) held.remove(handle);
    }
}
