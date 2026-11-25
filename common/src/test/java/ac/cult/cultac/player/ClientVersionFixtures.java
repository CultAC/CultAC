package ac.cult.cultac.player;

import ac.cult.cultac.network.protocol.ClientVersion;
import java.lang.reflect.Field;

/** Test-only override of the client version a player resolves. */
public final class ClientVersionFixtures {
    private ClientVersionFixtures() {}

    /** Makes {@code player} report {@code version} without a connection that negotiated it. */
    public static void setClientVersion(CultPlayer player, ClientVersion version) {
        try {
            Field field = CultPlayer.class.getDeclaredField("resolvedClientVersion");
            field.setAccessible(true);
            field.set(player, version);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
