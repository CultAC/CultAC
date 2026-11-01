package ac.cult.cultac.network;

import ac.cult.cultac.network.routing.NetworkModule;
import ac.cult.cultac.platform.api.sender.Sender;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Finds online players on every instance of the network and runs commands on the instance holding one. Without a
 * messaging transport it knows only this instance's players and never forwards a command.
 */
public interface NetworkRouting {

    /** This instance's routing: the running network service's, else one that knows no player and runs nothing. */
    static NetworkRouting current() {
        return NetworkModule.routing();
    }

    /** False while no transport is running; then {@link #roster} holds only this instance's players. */
    boolean enabled();

    /** This instance's id, as {@link NetworkPlayer#instance} names it. */
    String instanceId();

    /** The online player with this name (case-insensitive) or UUID, on any instance. */
    Optional<NetworkPlayer> locate(String nameOrUuid);

    /** Every online player the network knows of, this instance's included. */
    Collection<NetworkPlayer> roster();

    /**
     * Runs {@code line}, a CultAC command starting with one of its registered roots
     * ({@code CloudCommandService.primaryRoot()}) and without a leading slash, as {@code sender} on the instance
     * holding {@code where}, and relays its output to {@code sender}. Completes true once the command has run there,
     * whatever it answered: its own errors reach {@code sender} as they would locally. Completes false when the player
     * could not be reached, after telling {@code sender} why.
     */
    CompletableFuture<Boolean> run(Sender sender, String line, NetworkPlayer where);

    /** Whether another engine generation of this instance holds {@code player}, so commands about them run there. */
    default boolean otherGeneration(UUID player) {
        return false;
    }
}
