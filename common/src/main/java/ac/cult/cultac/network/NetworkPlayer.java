package ac.cult.cultac.network;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * An online player as the network roster lists them: the instance holding their connection, where that instance is a
 * proxy the backend server they are on, and their client brand, null until it is known.
 */
public record NetworkPlayer(
        UUID uuid,
        String name,
        String instance,
        @Nullable String server,
        @Nullable String brand) {
    public NetworkPlayer {
        brand = ClientBrandText.sanitize(brand);
    }

    public NetworkPlayer(UUID uuid, String name, String instance, @Nullable String server) {
        this(uuid, name, instance, server, null);
    }
}
