package ac.cult.cultac.network;

import org.jetbrains.annotations.Nullable;

/** Holds the routing {@link NetworkRouting#current} returns: the network service's while a transport runs. */
public final class RoutingHolder {
    static volatile NetworkRouting current = new LocalRouting();

    private RoutingHolder() {}

    /** Makes {@code routing} the current routing. */
    public static synchronized void install(NetworkRouting routing) {
        current = routing;
    }

    /** Restores the local-only routing while {@code routing}, or any routing when null, is the current one. */
    public static synchronized void uninstall(@Nullable NetworkRouting routing) {
        if (routing == null || current == routing) current = new LocalRouting();
    }
}
