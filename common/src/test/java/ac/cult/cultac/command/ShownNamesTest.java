package ac.cult.cultac.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ac.cult.cultac.bedrock.replay.offline.OfflinePlayers;
import ac.cult.cultac.network.NetworkPlayer;
import ac.cult.cultac.player.CultPlayer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShownNamesTest {

    @Test
    void aPlayerListedUnderAnotherNameOnTheirOwnClientMatchesTheirAccount() {
        OfflinePlayers.installConfig();
        CultPlayer player = OfflinePlayers.player();
        try {
            String account = player.getName();
            assertEquals(List.of(), ShownNames.listedHere(List.of(player), "Shown"));

            player.shownName = "Shown";
            assertEquals(
                    List.of(new ShownNames.Match(player.playerUUID, account, "Shown")),
                    ShownNames.listedHere(List.of(player), "sHOWN"));

            player.shownName = account.toUpperCase(Locale.ROOT);
            assertEquals(List.of(), ShownNames.listedHere(List.of(player), account));
        } finally {
            OfflinePlayers.close(player);
        }
    }

    @Test
    void theBackendsNameComesBeforeTheOwnEntrysProfileName() {
        OfflinePlayers.installConfig();
        CultPlayer player = OfflinePlayers.player();
        try {
            String account = player.getName();
            player.listed(account);
            assertNull(player.shownName);
            player.listed("Profile");
            assertEquals("Profile", player.shownName);

            player.backendName("Vk4817");
            assertEquals("Vk4817", player.shownName);
            assertEquals(
                    List.of(new ShownNames.Match(player.playerUUID, account, "Vk4817")),
                    ShownNames.listedHere(List.of(player), "vk4817"));

            player.backendName(account);
            assertEquals("Profile", player.shownName);
            player.backendName("Vk4817");
            player.backendName(null);
            player.listed(null);
            assertNull(player.shownName);
        } finally {
            OfflinePlayers.close(player);
        }
    }

    @Test
    void aListedProfileMatchesTheOnlineAccountWhenItsNameDiffers() {
        UUID uuid = UUID.randomUUID();
        NetworkPlayer online = new NetworkPlayer(uuid, "Account", "p9", "lobby");

        assertEquals(
                new ShownNames.Match(uuid, "Account", "Shown"),
                ShownNames.account(uuid, "Shown", id -> id.equals(uuid) ? online : null));
        assertNull(ShownNames.account(uuid, "account", id -> online));
        assertNull(ShownNames.account(uuid, "Shown", id -> null));
    }

    @Test
    void theSendersOwnViewComesFirstAndANameListedForSeveralMatchesNone() {
        UUID seen = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Map<UUID, NetworkPlayer> online = Map.of(
                seen, new NetworkPlayer(seen, "Seen", "p3", null),
                other, new NetworkPlayer(other, "Other", "p3", null));
        ShownNames.Match here = new ShownNames.Match(UUID.randomUUID(), "Here", "Shown");
        ShownNames.Match alsoHere = new ShownNames.Match(UUID.randomUUID(), "AlsoHere", "Shown");

        assertEquals(
                new ShownNames.Match(seen, "Seen", "Shown"),
                ShownNames.choose(List.of(seen), List.of(here), "Shown", online::get));
        assertEquals(here, ShownNames.choose(List.of(), List.of(here), "Shown", online::get));
        assertNull(ShownNames.choose(List.of(seen, other), List.of(here), "Shown", online::get));
        assertNull(ShownNames.choose(List.of(), List.of(here, alsoHere), "Shown", online::get));
    }

    @Test
    void aViewedProfileOfNoOnlineAccountLeavesTheChoiceToTheListedPlayers() {
        UUID unknown = UUID.randomUUID();
        ShownNames.Match here = new ShownNames.Match(UUID.randomUUID(), "Here", "Shown");
        ShownNames.Match alsoHere = new ShownNames.Match(UUID.randomUUID(), "AlsoHere", "Shown");

        assertEquals(here, ShownNames.choose(List.of(unknown), List.of(here), "Shown", id -> null));
        assertNull(ShownNames.choose(List.of(unknown), List.of(here, alsoHere), "Shown", id -> null));
    }

    @Test
    void aNameListedOnAnyInstanceMatchesOnceAndSeveralPlayersUnderItMatchNone() {
        UUID nicked = UUID.randomUUID();
        UUID seen = UUID.randomUUID();
        NetworkPlayer elsewhere = new NetworkPlayer(nicked, "Account", "p9", "lobby", null, Map.of(), "Nick");
        NetworkPlayer sameOnProxy = new NetworkPlayer(nicked, "Account", "p3", "lobby", null, Map.of(), "Nick");
        NetworkPlayer unlisted = new NetworkPlayer(UUID.randomUUID(), "Nick", "p7", null);
        Map<UUID, NetworkPlayer> online = Map.of(seen, new NetworkPlayer(seen, "Seen", "p3", null));
        ShownNames.Match match = new ShownNames.Match(nicked, "Account", "Nick");

        List<ShownNames.Match> listed = ShownNames.listed(List.of(), List.of(elsewhere, sameOnProxy, unlisted), "nick");
        assertEquals(List.of(match), listed);
        assertEquals(match, ShownNames.choose(List.of(), listed, "nick", online::get));
        assertEquals(
                new ShownNames.Match(seen, "Seen", "nick"),
                ShownNames.choose(List.of(seen), listed, "nick", online::get));

        NetworkPlayer another = new NetworkPlayer(UUID.randomUUID(), "Other", "p7", null, null, Map.of(), "NICK");
        assertNull(ShownNames.choose(
                List.of(),
                ShownNames.listed(List.of(match), List.of(elsewhere, another), "Nick"),
                "Nick",
                online::get));
    }
}
