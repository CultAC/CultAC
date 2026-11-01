package ac.cult.cultac.command.profile;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Profiles of players connected through another instance of the network, such as another proxy, where the platform
 * can reach that instance. The instance holding the player renders the profile from its live state.
 */
public interface RemoteProfiles {
    RemoteProfiles NONE = new RemoteProfiles() {
        @Override
        public @Nullable Remote locate(String name) {
            return null;
        }

        @Override
        public CompletableFuture<List<Component>> request(Remote remote) {
            return CompletableFuture.failedFuture(new IllegalStateException("No remote instances"));
        }
    };

    /** A player online through another instance; {@code server} is null when the network does not say. */
    record Remote(
            UUID uuid,
            String name,
            String instance,
            @Nullable String server) {}

    /** Where the named player is online through another instance; null when not online elsewhere or unknown. */
    @Nullable
    Remote locate(String name);

    /** The profile lines the holding instance rendered; completes exceptionally when it does not answer in time. */
    CompletableFuture<List<Component>> request(Remote remote);

    static RemoteProfiles current() {
        return Holder.current;
    }

    static void install(RemoteProfiles profiles) {
        Holder.current = profiles == null ? NONE : profiles;
    }

    final class Holder {
        private static volatile RemoteProfiles current = NONE;

        private Holder() {}
    }
}
