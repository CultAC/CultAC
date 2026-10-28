package ac.cult.cultac.command.commands;

import ac.cult.cultac.network.NetworkPlayer;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * Finds the player a history command names, in order: online anywhere on the network, then this server's identity
 * store, then a UUID typed in full or Mojang's profile API. The roster answers on the calling thread; the identity store
 * is asked on the given executor, and the rest goes through {@link TargetLookup}.
 */
final class HistoryTargets {
    private final Function<String, Optional<NetworkPlayer>> roster;
    private final Function<String, Optional<UUID>> identities;
    private final TargetLookup lookup;
    private final Executor executor;

    /**
     * @param roster     an online player by name or UUID, from memory
     * @param identities a player this server has seen, by name; may block
     * @param lookup     a UUID typed in full, or Mojang's profile API
     */
    HistoryTargets(
            Function<String, Optional<NetworkPlayer>> roster,
            Function<String, Optional<UUID>> identities,
            TargetLookup lookup,
            Executor executor) {
        this.roster = roster;
        this.identities = identities;
        this.lookup = lookup;
        this.executor = executor;
    }

    CompletableFuture<TargetLookup.Outcome> resolve(String input) {
        Optional<NetworkPlayer> online = roster.apply(input);
        if (online.isPresent()) {
            NetworkPlayer player = online.get();
            return found(player.uuid(), player.name());
        }
        return CompletableFuture.supplyAsync(() -> identities.apply(input), executor)
                .thenCompose(known -> known.isPresent() ? found(known.get(), input) : lookup.resolve(input));
    }

    private static CompletableFuture<TargetLookup.Outcome> found(UUID id, String name) {
        return CompletableFuture.completedFuture(new TargetLookup.Outcome(new CloudArguments.Target(id, name), null));
    }
}
