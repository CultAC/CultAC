package ac.cult.cultac.network;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
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
        @Nullable String brand,
        Map<String, List<String>> details) {
    public NetworkPlayer {
        brand = ClientBrandText.sanitize(brand);
        details = details.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
    }

    public NetworkPlayer(UUID uuid, String name, String instance, @Nullable String server, @Nullable String brand) {
        this(uuid, name, instance, server, brand, Map.of());
    }

    public NetworkPlayer(UUID uuid, String name, String instance, @Nullable String server) {
        this(uuid, name, instance, server, null);
    }
}
