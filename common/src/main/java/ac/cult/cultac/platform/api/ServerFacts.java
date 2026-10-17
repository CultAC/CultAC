package ac.cult.cultac.platform.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * What a server or player contributes to a capture's JOIN: the server's target tick length in microseconds (0 when it
 * has none or cannot tell), its software and version (null when unknown), and settings that change combat, by key. A
 * setting that cannot be read is left out.
 */
public record ServerFacts(int tickMicros, @Nullable String server, Map<String, String> settings) {
    public static final ServerFacts NONE = new ServerFacts(0, null, Map.of());

    public ServerFacts {
        tickMicros = Math.max(0, tickMicros);
        settings = settings == null || settings.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(settings));
    }

    /** Settings alone, as a player's facts are. */
    public static ServerFacts settings(Map<String, String> settings) {
        return new ServerFacts(0, null, settings);
    }

    /**
     * These facts with {@code over}'s laid on top: its settings replace these of the same key, and its tick length and
     * server replace these when it has them.
     */
    public ServerFacts merge(ServerFacts over) {
        if (over == null || over.equals(NONE)) return this;
        if (equals(NONE)) return over;
        Map<String, String> merged = new LinkedHashMap<>(settings);
        merged.putAll(over.settings);
        return new ServerFacts(
                over.tickMicros != 0 ? over.tickMicros : tickMicros,
                over.server != null ? over.server : server,
                merged);
    }

    /** These facts with setting {@code key} set to {@code value}. */
    public ServerFacts with(String key, String value) {
        Map<String, String> merged = new LinkedHashMap<>(settings);
        merged.put(key, value);
        return new ServerFacts(tickMicros, server, merged);
    }
}
