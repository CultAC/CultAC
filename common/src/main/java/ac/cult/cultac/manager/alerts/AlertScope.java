package ac.cult.cultac.manager.alerts;

import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/** Which flagged players a staff member's alerts, verbose and brand messages cover. */
public enum AlertScope {
    /** Players on the staff member's own backend server; on a server without a proxy, that whole server. */
    SERVER,
    /** Players on every instance of the network; needs a messaging transport. */
    GLOBAL;

    /** The name commands and the datastore use. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static @Nullable AlertScope parse(@Nullable String id) {
        return AlertKind.parse(values(), id);
    }

    /**
     * Whether staff with this scope on {@code staffServer} get an alert about a player on {@code subjectServer}.
     * {@code local}: this instance raised it. {@code proxy}: this instance is a proxy, whose players are on backend
     * servers; otherwise this instance is the server. A null server is unknown and matches none.
     */
    public boolean covers(@Nullable String staffServer, @Nullable String subjectServer, boolean local, boolean proxy) {
        if (this == GLOBAL) return true;
        if (local && !proxy) return true;
        return staffServer != null && staffServer.equals(subjectServer);
    }
}
