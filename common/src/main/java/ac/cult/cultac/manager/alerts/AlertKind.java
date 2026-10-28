package ac.cult.cultac.manager.alerts;

import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/** The kinds of staff message a flagged player raises, as the network's {@code alert} messages name them. */
public enum AlertKind {
    ALERT,
    VERBOSE,
    BRAND;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static @Nullable AlertKind parse(@Nullable String id) {
        if (id == null) return null;
        for (AlertKind kind : values()) {
            if (kind.id().equals(id)) return kind;
        }
        return null;
    }
}
