package ac.cult.cultac.network;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * An online player as the network roster lists them: the instance holding their connection and, where that instance
 * is a proxy, the backend server they are on.
 */
public record NetworkPlayer(
        UUID uuid, String name, String instance, @Nullable String server) {}
