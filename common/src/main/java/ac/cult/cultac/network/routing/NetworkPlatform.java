package ac.cult.cultac.network.routing;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * What the platform tells the network service about this instance. The platform also reports each join, quit and
 * backend switch through {@link NetworkModule#playerChanged} and {@link NetworkModule#playerLeft}.
 */
public interface NetworkPlatform {

    /** Every player online on this instance, whichever runtime holds their connection. Any thread. */
    Collection<OnlinePlayer> onlinePlayers();

    /** Which engine generation holds {@code uuid}'s connection here. Any thread. */
    default Holder holder(UUID uuid) {
        return Holder.THIS;
    }

    /** The platform sender a forwarded command's sender stands on; null where any sender object will do. */
    default @Nullable Object remoteSenderBase() {
        return null;
    }

    /** {@code <host>:<port>}, the instance id when nothing else names one. */
    String address();

    /** Whether this instance is a proxy, whose players are on backend servers. */
    default boolean proxy() {
        return false;
    }

    /** RedisBungee's id for this proxy, as found when the platform started; null when RedisBungee is not installed. */
    default @Nullable String redisBungeeId() {
        return null;
    }

    /** Velocity's forwarding secret, the signing key unless {@code network.secret} is set; null where there is none. */
    default byte @Nullable [] defaultSecret() {
        return null;
    }

    /** A transport over RedisBungee's pub/sub; null where RedisBungee is not installed or cannot be reached. */
    default @Nullable MessageTransport redisBungee() {
        return null;
    }

    /** A player online here; {@code server} is their backend server on a proxy and null elsewhere. */
    record OnlinePlayer(UUID uuid, String name, @Nullable String server) {}

    /** The generation holding a player's connection, relative to the one asking. */
    enum Holder {
        /** This generation, or the only one there is. */
        THIS,
        /** Another engine generation in this process. */
        OTHER,
        /** No generation: the player is not connected here, or no runtime took their connection. */
        NONE
    }

    /** {@code <host>:<port>}, this machine's name standing in for a null or empty {@code host}. */
    static String address(@Nullable String host, int port) {
        if (host == null || host.isBlank()) {
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException unnamed) {
                host = "localhost";
            }
        }
        return host + ":" + port;
    }
}
