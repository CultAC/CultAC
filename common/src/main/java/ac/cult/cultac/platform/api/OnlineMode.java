package ac.cult.cultac.platform.api;

/** Whether the players on a server are authenticated with Mojang. */
public final class OnlineMode {
    private OnlineMode() {}

    /**
     * True when the server verifies logins itself, or sits behind a proxy that does: BungeeCord or Velocity forwarding
     * with that proxy's online mode on, as the server's config states it.
     */
    public static boolean authenticated(
            boolean serverOnline,
            boolean bungeeForwarding,
            boolean bungeeOnline,
            boolean velocityForwarding,
            boolean velocityOnline) {
        return serverOnline || (bungeeForwarding && bungeeOnline) || (velocityForwarding && velocityOnline);
    }
}
