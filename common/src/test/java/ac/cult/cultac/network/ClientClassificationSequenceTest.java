package ac.cult.cultac.network;

import static ac.cult.cultac.player.ClientClassification.Type.*;
import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.bedrock.player.BedrockPlayerState;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.bridge.protocol.BridgeMessage;
import ac.cult.cultac.checks.impl.misc.BrandSpoofA;
import ac.cult.cultac.checks.impl.misc.BrandSpoofB;
import ac.cult.cultac.checks.impl.misc.ClientBrand;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.network.protocol.player.User;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientClassificationSequenceTest {
    private CultPlayer player(String brand) {
        OfflineCultTestBootstrap.installConfig();
        var user = TestUsers.create(new User.Profile(UUID.randomUUID(), "Client"), new EmbeddedChannel());
        var player = new CultPlayer(user);
        TestUsers.attach(user, player);
        player.checkManager.getCheck(ClientBrand.class).restore(brand);
        return player;
    }

    @Test
    void bedrockIdentityDoesNotUseJavaBrandChannels() {
        OfflineCultTestBootstrap.installConfig();
        UUID uuid = UUID.randomUUID();
        var user = TestUsers.create(new User.Profile(uuid, "Bedrock"), new EmbeddedChannel());
        var player = new CultPlayer(user, MovementPlatform.BEDROCK, new BedrockPlayerState(uuid));
        player.checkManager.getCheck(ClientBrand.class).restore("vanilla");
        player.pluginChannelManager.handleChannelRegistered("fabric:registry/sync/direct");
        assertEquals(BEDROCK, player.getClientClassification().brandType());
        assertFalse(player.getClientClassification().contradictsVanilla());
        var check = player.checkManager.getCheck(BrandSpoofA.class);
        check.onMove(null, player, null);
        assertEquals(0, check.getViolations());
    }

    @Test
    void contradictoryRegistrationFlagsOnceDuringPlay() {
        var player = player("vanilla");
        var check = player.checkManager.getCheck(BrandSpoofA.class);
        check.onMove(null, player, null);
        assertEquals(0, check.getViolations());
        player.pluginChannelManager.handleChannelRegistered("fabric:registry/sync/direct");
        check.onMove(null, player, null);
        check.onMove(null, player, null);
        assertEquals(1, check.getViolations());
    }

    @Test
    void brandChangesFlagOnceWhenEnabled() {
        var player = player("vanilla");
        var check = player.checkManager.getCheck(BrandSpoofB.class);
        check.setEnabled(true);
        var brand = player.checkManager.getCheck(ClientBrand.class);
        brand.observe("vanilla");
        brand.observe("vanilla (Velocity)");
        assertEquals(0, check.getViolations());
        brand.observe("fabric");
        brand.observe("forge");
        assertEquals(1, check.getViolations());
    }

    @Test
    void registrationsAfterBrandAndUnregistrationsUpdateTheSnapshot() {
        var player = player("fabric");
        assertEquals(UNSET, player.getClientClassification().channelType());
        player.pluginChannelManager.handleChannelRegistered("fabric:registry/sync/direct");
        assertEquals("brandtype=FABRIC", player.getClientClassification().display());
        player.pluginChannelManager.handleChannelUnregistered("fabric:registry/sync/direct");
        assertEquals(UNSET, player.getClientClassification().channelType());
    }

    @Test
    void apolloReportIsIndependentOfBrandAndChannels() {
        var player = player("custom");
        player.clientMods = new BridgeMessage.ClientMods(0, true, List.of());
        assertEquals(
                "brandtype=UNKNOWN channeltype=UNSET modtype=LUNAR",
                player.getClientClassification().display());
    }

    @Test
    void vanillaWithFabricRegistrationIsContradictory() {
        var player = player("vanilla");
        assertFalse(player.getClientClassification().contradictsVanilla());
        player.pluginChannelManager.handleChannelRegistered("fabric:registry/sync/direct");
        assertTrue(player.getClientClassification().contradictsVanilla());
    }
}
