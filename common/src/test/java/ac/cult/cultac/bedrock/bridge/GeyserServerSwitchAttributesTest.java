package ac.cult.cultac.bedrock.bridge;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.bedrock.player.BedrockPlayerState;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.prediction.integration.BedrockProfileState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundLogin;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.UUID;
import org.cloudburstmc.protocol.bedrock.data.AttributeData;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket;
import org.geysermc.geyser.session.GeyserSession;
import org.junit.Test;

/**
 * A proxy server switch sends the Java client a second LOGIN. Geyser keeps its session player, so the Bedrock client
 * keeps applying the attributes written to its actor: the powder snow frost the new backend sends among them.
 */
public class GeyserServerSwitchAttributesTest {
    private static final long ACTOR = 3;

    @Test
    public void attributesWrittenToTheActorAfterAServerSwitchReachTheEngine() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = bedrockPlayer();
        try {
            var processor = player.checkManager.getSimulationProcessor();
            processor.handleBedrockActorCreation(ACTOR);
            process(player, 1);
            assertEquals(0.1F, state(player).movementAttribute().current(), 0);

            var login = new ClientboundLogin(
                    1,
                    true,
                    new PlayerSpawnInfo(
                            ModelRegistryNamesState.defaults().id("minecraft:dimension_type", "minecraft:overworld"),
                            "minecraft:overworld",
                            GameMode.SURVIVAL));
            var before = player.compensatedEntities.getSelf();
            new PacketPlayerRespawn()
                    .onLogin(
                            new PacketSendEvent<>(
                                    player.user, ConnectionPhase.PLAY, ClientboundPackets.LOGIN, login, false),
                            player,
                            login);
            var self = player.compensatedEntities.getSelf();
            assertNotSame(before, self);
            assertEquals(ACTOR, self.bedrockRuntimeId);

            // The new backend's frost, written to the same actor and proven by the following marker.
            capture(player, 0.12117857F);
            assertEquals(0.12117857F, self.bedrockAttributes.movement().current(), 0);
            process(player, 2);
            assertEquals(0.12117857F, state(player).movementAttribute().current(), 0);
        } finally {
            close(player);
        }
    }

    private static void capture(CultPlayer player, float movement) throws Exception {
        var session = mock(GeyserSession.class, RETURNS_DEEP_STUBS);
        when(session.getPlayerEntity().geyserId()).thenReturn(ACTOR);
        var packet = new UpdateAttributesPacket();
        packet.setRuntimeEntityId(ACTOR);
        packet.setAttributes(List.of(new AttributeData("minecraft:movement", 0, 1024, movement, movement)));
        var attributes = new GeyserEntityAttributes();
        attributes.capture(session, player, GeyserReplayUpdate.capture(packet));
        assertTrue(attributes.hasPending());
        var constructor = CultPlayer.BedrockTransaction.class.getDeclaredConstructor(int.class, int.class);
        constructor.setAccessible(true);
        var marker = constructor.newInstance(-1, 1);
        var acknowledged = CultPlayer.BedrockTransaction.class.getDeclaredField("acknowledged");
        acknowledged.setAccessible(true);
        acknowledged.setBoolean(marker, true);
        attributes.boundary(player, marker);
    }

    private static void process(CultPlayer player, long tick) {
        var position = new Vec3(2, 65, 3);
        var frame = BedrockAuthInputFrame.builder(player.user.getUUID())
                .protocolVersion(0)
                .clientTick(tick)
                .inputMode(1)
                .playMode(2)
                .deviceId(3)
                .position(position)
                .packetPosition(position.add(0, 1.62, 0))
                .delta(Vec3.ZERO)
                .reportedEndOfTickVelocity(Vec3.ZERO)
                .rotation(0, 0, 0)
                .moveVector(0, 0)
                .authorityMode("client-auth-input")
                .build();
        player.bedrockState.offerAuthInputFrame(frame);
        assertNotNull(player.checkManager
                .getSimulationProcessor()
                .processBedrockAuthInputFrame(frame, BedrockPredictionTrigger.OFFLINE_REPLAY));
    }

    private static BedrockMovementState state(CultPlayer player) {
        return BedrockProfileState.previousState(player.checkManager
                .getSimulationProcessor()
                .getCurrentPredictionCommit()
                .carry());
    }

    private static CultPlayer bedrockPlayer() {
        UUID playerId = UUID.fromString("5d0f6c1e-0b4a-4d39-9a52-6f0e8f4e2b11");
        var platform = mock(ac.cult.cultac.network.PlatformConnection.class);
        when(platform.initialWorldData())
                .thenReturn(ac.cult.cultac.utils.latency.ClientWorldRegistries.modelDefaults());
        var connection = new ac.cult.cultac.network.CultConnection(
                platform,
                new EmbeddedChannel(),
                ac.cult.cultac.CultAPI.INSTANCE.getNetworkManager().dispatcher(),
                ignored -> null);
        for (var direction : ac.cult.cultac.protocol.PacketDirection.values())
            connection.phase(direction, ConnectionPhase.PLAY);
        User user = new User(new User.Profile(playerId, ".Switch_Test"), connection);
        return new CultPlayer(user, MovementPlatform.BEDROCK, new BedrockPlayerState(playerId));
    }

    private static void close(CultPlayer player) {
        Object channel = player.user.getChannel();
        player.onRemove();
        if (channel instanceof EmbeddedChannel embedded) {
            embedded.runPendingTasks();
            embedded.runScheduledPendingTasks();
            embedded.finishAndReleaseAll();
        }
    }
}
