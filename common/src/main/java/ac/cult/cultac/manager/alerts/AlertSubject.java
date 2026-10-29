package ac.cult.cultac.manager.alerts;

import ac.cult.cultac.network.PlatformConnection;
import ac.cult.cultac.player.CultPlayer;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * The player an alert is about: their id, their name and the backend server they are on behind a proxy. Each is null
 * when unknown; an alert about an unknown server reaches {@link AlertScope#GLOBAL} staff only, except on a server
 * without a proxy, where every alert raised there is about that server. An alert about {@link #NONE} has no server to
 * scope it to: raised here it reaches every subscriber, received it reaches global staff only.
 */
public record AlertSubject(
        @Nullable UUID uuid,
        @Nullable String name,
        @Nullable String server) {
    public static final AlertSubject NONE = new AlertSubject(null, null, null);

    public static AlertSubject of(CultPlayer player) {
        PlatformConnection platform = player.user.getCultConnection().platform();
        return new AlertSubject(
                player.getUniqueId(), player.getName(), platform == null ? null : platform.backendName());
    }

    /** Whether the alert is about no player. */
    public boolean none() {
        return uuid == null;
    }

    /** This player on {@code server}. */
    public AlertSubject on(@Nullable String server) {
        return new AlertSubject(uuid, name, server);
    }
}
