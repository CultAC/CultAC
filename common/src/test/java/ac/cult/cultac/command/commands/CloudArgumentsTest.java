package ac.cult.cultac.command.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.network.NetworkPlayer;
import ac.cult.cultac.network.NetworkRouting;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.player.CultPlayer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class CloudArgumentsTest {
    private static final NetworkPlayer ALICE = new NetworkPlayer(UUID.randomUUID(), "Alice", "p7", "lobby");

    private final CultPlayer bob = player("Bob");
    private final Roster roster = new Roster();

    /** A roster listing this instance's Bob and the given players elsewhere. */
    private final class Roster implements NetworkRouting {
        final List<NetworkPlayer> players = new ArrayList<>();
        final List<String> located = new ArrayList<>();

        Roster() {
            players.add(new NetworkPlayer(bob.getUniqueId(), "Bob", "p3", null));
            players.add(ALICE);
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public String instanceId() {
            return "p3";
        }

        @Override
        public Optional<NetworkPlayer> locate(String nameOrUuid) {
            located.add(nameOrUuid);
            return players.stream()
                    .filter(player -> player.name().equalsIgnoreCase(nameOrUuid))
                    .findFirst();
        }

        @Override
        public Collection<NetworkPlayer> roster() {
            return players;
        }

        @Override
        public CompletableFuture<Boolean> run(Sender sender, String line, NetworkPlayer where) {
            throw new UnsupportedOperationException();
        }
    }

    private static CultPlayer player(String name) {
        CultPlayer player = mock(CultPlayer.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        return player;
    }

    @Test
    void aLocalPlayerIsFoundHereWithoutTheRoster() {
        assertEquals(
                new CloudArguments.Target(bob.getUniqueId(), "Bob"),
                CloudArguments.online("bob", List.of(bob), roster));
        assertEquals(List.of(), roster.located);
    }

    @Test
    void aPlayerOnAnotherInstanceIsFoundThroughTheRoster() {
        assertEquals(
                new CloudArguments.Target(ALICE.uuid(), "Alice"), CloudArguments.online("ALICE", List.of(bob), roster));
        assertNull(CloudArguments.online("Carol", List.of(bob), roster));
    }

    @Test
    void namesJoinTheRosterWithoutDuplicatesIgnoringCase() {
        roster.players.add(new NetworkPlayer(UUID.randomUUID(), "bob", "p9", null));
        roster.players.add(new NetworkPlayer(UUID.randomUUID(), "Adam", "p9", "survival"));

        assertEquals(List.of("Adam", "Alice", "Bob"), List.copyOf(CloudArguments.names(List.of(bob), roster.roster())));
    }

    @Test
    void suggestionsStayWithinTheCap() {
        for (int i = 0; i < CloudArguments.MAX_SUGGESTIONS + 20; i++)
            roster.players.add(new NetworkPlayer(UUID.randomUUID(), "Al" + i, "p9", null));

        List<String> matching = CloudArguments.matching(CloudArguments.names(List.of(bob), roster.roster()), "al");
        assertEquals(CloudArguments.MAX_SUGGESTIONS, matching.size());
    }
}
