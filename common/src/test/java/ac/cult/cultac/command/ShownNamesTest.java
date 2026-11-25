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
}
