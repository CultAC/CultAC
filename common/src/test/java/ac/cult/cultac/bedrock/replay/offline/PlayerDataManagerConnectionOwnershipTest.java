package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.PlayerDataManager;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import org.junit.Test;

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

    @Test
    public void matchedBridgeSelectsBedrockBeforeUuidRegistrationWithoutChangingAnotherConnection() {
        OfflineCultTestBootstrap.installConfig();
        PlayerDataManager manager = new PlayerDataManager();
        Object bridge = new Object();
        User bedrock = OfflineCultTestBootstrap.wireUser(new User.Profile(SHARED_UUID, "bedrock"), bridge);
        User java = user("java");
        try {
            // This UUID has no Floodgate prefix or Geyser API registration.
            manager.addUser(bedrock);
            manager.addUser(java);

            assertSame(bridge, bedrock.getCultConnection().bedrockBridge());
            assertTrue(manager.getPlayer(bedrock).isBedrockMovement());
            assertFalse(manager.getPlayer(java).isBedrockMovement());
        } finally {
            manager.remove(bedrock);
            manager.remove(java);
            ((EmbeddedChannel) bedrock.getChannel()).finishAndReleaseAll();
            ((EmbeddedChannel) java.getChannel()).finishAndReleaseAll();
        }
    }

    private static User user(String name) {
        return TestUsers.create(new User.Profile(SHARED_UUID, name), new EmbeddedChannel());
    }
}
