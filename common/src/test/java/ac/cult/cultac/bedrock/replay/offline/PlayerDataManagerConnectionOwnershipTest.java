package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.PlayerDataManager;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class PlayerDataManagerConnectionOwnershipTest {
    private static final UUID SHARED_UUID = UUID.fromString("d4624974-6c22-4635-b82d-8d72e998e5d6");

    @Test
    public void sameUuidConnectionsRemainIndependentlyTracked() {
        OfflineCultTestBootstrap.installConfig();
        PlayerDataManager manager = new PlayerDataManager();
        User oldConnection = user("old");
        User replacement = user("replacement");
        try {
            manager.addUser(oldConnection);
            manager.addUser(replacement);

            CultPlayer oldPlayer = manager.getPlayer(oldConnection);
            CultPlayer replacementPlayer = manager.getPlayer(replacement);
            manager.exemptUser(replacement);
            assertSame(oldPlayer, manager.getPlayer(oldConnection));
            assertSame(replacementPlayer, manager.getPlayer(replacement));
            assertTrue(manager.size() == 2);

            assertTrue(manager.onDisconnect(oldConnection));
            assertFalse(manager.getEntries().contains(oldPlayer));
            assertSame(replacementPlayer, manager.getPlayer(replacement));
            assertTrue(manager.isExemptUser(replacement));
            assertTrue(manager.size() == 1);
        } finally {
            manager.clearExemptions(replacement);
            manager.remove(replacement);
            ((EmbeddedChannel) oldConnection.getChannel()).finishAndReleaseAll();
            ((EmbeddedChannel) replacement.getChannel()).finishAndReleaseAll();
        }
    }

    @Test
    public void exemptionDoesNotTransferToReplacementWithSameUuid() {
        OfflineCultTestBootstrap.installConfig();
        PlayerDataManager manager = new PlayerDataManager();
        User exemptConnection = user("exempt");
        User replacement = user("replacement");
        try {
            manager.exemptUser(exemptConnection);

            assertTrue(manager.isExemptUser(exemptConnection));
            assertFalse(manager.isExemptUser(replacement));
            assertFalse(manager.shouldCheck(exemptConnection));
            assertTrue(manager.shouldCheck(replacement));

            manager.clearExemptions(exemptConnection);
            assertTrue(manager.shouldCheck(replacement));
        } finally {
            manager.clearExemptions(exemptConnection);
            manager.remove(replacement);
            ((EmbeddedChannel) exemptConnection.getChannel()).finishAndReleaseAll();
            ((EmbeddedChannel) replacement.getChannel()).finishAndReleaseAll();
        }
    }

    private static User user(String name) {
        return new User(
                new User.Profile(SHARED_UUID, name),
                null,
                null,
                null,
                new EmbeddedChannel());
    }
}
