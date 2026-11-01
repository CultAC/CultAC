package ac.cult.cultac.utils.anticheat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Logs a recurring failure once with its stack trace, then at most once an interval per key with how often it recurred,
 * so a failure on every packet or event cannot flood the console from a server or I/O thread.
 */
public final class LogThrottle {
    static final long DEFAULT_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);
    /** Keys tracked apart; past it every new key shares one entry. */
    static final int MAX_KEYS = 256;
    /** The most characters {@link #clip} keeps. */
    public static final int MAX_CLIPPED = 200;

    private static final String OVERFLOW_KEY = "\0other";

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final long intervalNanos;
    private final LongSupplier clock;

    private static final class Entry {
        final AtomicLong suppressed = new AtomicLong();
        final AtomicLong nextNanos;

        Entry(long nextNanos) {
            this.nextNanos = new AtomicLong(nextNanos);
        }
    }

    public LogThrottle() {
        this(DEFAULT_INTERVAL_NANOS, System::nanoTime);
    }

    public LogThrottle(long intervalNanos, LongSupplier clock) {
        this.intervalNanos = intervalNanos;
        this.clock = clock;
    }

    /** Logs {@code message}, with {@code failure}'s stack trace when it is not null, throttled per {@code key}. */
    public void warn(String key, String message, Throwable failure) {
        try {
            long suppressed = admit(key);
            if (suppressed < 0) return;
            if (suppressed == 0) {
                if (failure == null) LogUtil.warn(message);
                else LogUtil.warn(message, failure);
                return;
            }
            String repeated = failure == null ? message : message + ": " + failure;
            LogUtil.warn(repeated + " (" + suppressed + " more since the last report)");
        } catch (Throwable unloggable) {
        }
    }

    /** {@link #warn} at error level; a failure to log reaches the caller. */
    public void error(String key, String message, Throwable failure) {
        long suppressed = admit(key);
        if (suppressed < 0) return;
        if (suppressed == 0) {
            if (failure == null) LogUtil.error(message);
            else LogUtil.error(message, failure);
            return;
        }
        String repeated = failure == null ? message : message + ": " + failure;
        LogUtil.error(repeated + " (" + suppressed + " more since the last report)");
    }

    /** Text another party controls, made safe to log: {@link #oneLine} at most {@link #MAX_CLIPPED} characters. */
    public static String clip(String text) {
        return text == null ? "null" : oneLine(text, MAX_CLIPPED);
    }

    /**
     * Text another party controls as one line of at most {@code max} characters: control, format and line or
     * paragraph separator characters and the section sign {@code §} become {@code ?}, and a longer text is cut
     * short with an ellipsis.
     */
    public static String oneLine(String text, int max) {
        int length = Math.min(text.length(), max);
        boolean cut = length < text.length();
        if (cut) {
            length--;
            if (length > 0 && Character.isHighSurrogate(text.charAt(length - 1))) length--;
        }
        StringBuilder clipped = new StringBuilder(length + 1);
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            int type = Character.getType(c);
            boolean unsafe = Character.isISOControl(c)
                    || c == '§'
                    || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR;
            clipped.append(unsafe ? '?' : c);
        }
        if (cut) clipped.append('\u2026');
        return clipped.toString();
    }

    /**
     * -1 when this occurrence is not logged; otherwise how many occurrences of {@code key} went unlogged before it, 0
     * for its first.
     */
    long admit(String key) {
        long now = clock.getAsLong();
        Entry entry = entries.get(key);
        if (entry == null) {
            if (entries.size() >= MAX_KEYS) key = OVERFLOW_KEY;
            Entry fresh = new Entry(now + intervalNanos);
            entry = entries.putIfAbsent(key, fresh);
            if (entry == null) return 0;
        }
        long next = entry.nextNanos.get();
        if (now - next < 0 || !entry.nextNanos.compareAndSet(next, now + intervalNanos)) {
            entry.suppressed.incrementAndGet();
            return -1;
        }
        return entry.suppressed.getAndSet(0);
    }
}
