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
        return parse(values(), id);
    }

    /** The constant of {@code values} whose lower-case name is {@code id}, ignoring case and surrounding space. */
    static <E extends Enum<E>> @Nullable E parse(E[] values, @Nullable String id) {
        if (id == null) return null;
        for (E value : values) {
            if (value.name().equalsIgnoreCase(id.trim())) return value;
        }
        return null;
    }
}
