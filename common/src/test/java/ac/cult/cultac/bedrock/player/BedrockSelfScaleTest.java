package ac.cult.cultac.bedrock.player;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.manager.player.SetbackFixtures;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.GameMode;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Geyser never sends a Bedrock client the Java scale attribute: the client learns its scale from its own entity
 * data. A Bedrock player's one scale is the value its client acknowledged there.
 */
class BedrockSelfScaleTest {
    private CultPlayer player;
    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        channel = new EmbeddedChannel();
        player = new CultPlayer(TestUsers.create(new User.Profile(UUID.randomUUID(), "Bedrock_Scale"), channel));
        Field version = CultPlayer.class.getDeclaredField("resolvedClientVersion");
        version.setAccessible(true);
        version.set(player, ClientVersion.V_26_3);
        player.gamemode = GameMode.SURVIVAL;
        SetbackFixtures.setLoaded(player.getSetbackTeleportUtil(), true);
        player.checkManager.getSimulationProcessor().handleRespawn();
    }

    @AfterEach
    void close() {
        player.onRemove();
        channel.runPendingTasks();
        channel.finishAndReleaseAll();
    }

    @Test
    void theJavaAttributeDoesNotScaleABedrockPlayer() {
        player.movementPlatform = MovementPlatform.BEDROCK;
        player.bedrockState = new BedrockPlayerState(player.playerUUID);

        attributes(new AttributeSnapshot("minecraft:scale", 2.0D, List.of()));
        assertEquals(1.0F, player.getScale());
        assertEquals(1.0F, player.dimensionScale);
    }

    @Test
    void theAcknowledgedScaleIsTheBedrockPlayersScaleUntilTheNextOne() {
        player.movementPlatform = MovementPlatform.BEDROCK;
        player.bedrockState = new BedrockPlayerState(player.playerUUID);

        player.compensatedEntities.getSelf().onBedrockScale(0.25F);
        assertEquals(0.25F, player.getScale());
        assertEquals(0.25F, player.dimensionScale);
        assertEquals(0.6F * 0.25F, player.boundingBox.maxX - player.boundingBox.minX, 1.0E-7);

        // Another attribute, or the Java scale again, leaves it alone.
        attributes(
                new AttributeSnapshot("minecraft:movement_speed", 0.2D, List.of()),
                new AttributeSnapshot("minecraft:scale", 4.0D, List.of()));
        assertEquals(0.25F, player.getScale());

        player.compensatedEntities.getSelf().onBedrockScale(1.5F);
        assertEquals(1.5F, player.dimensionScale);
    }

    @Test
    void aJavaPlayerStillTakesTheAttribute() {
        attributes(new AttributeSnapshot("minecraft:scale", 2.0D, List.of()));
        assertEquals(2.0F, player.getScale());
    }

    private void attributes(AttributeSnapshot... snapshots) {
        player.compensatedEntities.updateAttributes(
                player.entityID, List.of(snapshots), player.lastTransactionSent.get());
    }
}
