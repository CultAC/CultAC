package ac.cult.cultac.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ac.cult.cultac.bedrock.replay.offline.OfflinePlayers;
import ac.cult.cultac.network.NetworkPlayer;
import ac.cult.cultac.player.CultPlayer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShownNamesTest {

    @Test
    void aPlayerListedUnderAnotherNameOnTheirOwnClientMatchesTheirAccount() {
        OfflinePlayers.installConfig();
        CultPlayer player = OfflinePlayers.player();
        try {
            String account = player.getName();
            assertNull(ShownNames.listedHere(List.of(player), "Shown"));

            player.shownName = "Shown";
            ShownNames.Match match = ShownNames.listedHere(List.of(player), "sHOWN");
            assertEquals(new ShownNames.Match(player.playerUUID, account, "Shown"), match);

            player.shownName = account.toUpperCase(Locale.ROOT);
            assertNull(ShownNames.listedHere(List.of(player), account));
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
}
