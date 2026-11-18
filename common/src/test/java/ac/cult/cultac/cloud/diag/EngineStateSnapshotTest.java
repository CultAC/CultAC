package ac.cult.cultac.cloud.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.events.packets.PacketWorldBorder;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.data.packetentity.PacketEntitySelf;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The engine state a cut's header carries: what the engine holds for abilities, self entity data, effects and the
 * world border, one topic per line, and a topic whose read fails costs only that line.
 */
class EngineStateSnapshotTest {
    private CultPlayer player;

    @BeforeAll
    static void bootstrap() {
        OfflineCultTestBootstrap.installConfig();
    }

    @BeforeEach
    void player() {
        User user = TestUsers.create(new User.Profile(UUID.randomUUID(), "Diag_State"), new EmbeddedChannel());
        player = new CultPlayer(user);
    }

    @AfterEach
    void close() {
        EmbeddedChannel channel = (EmbeddedChannel) player.user.getChannel();
        player.onRemove();
        channel.finishAndReleaseAll();
    }

    @Test
    void aFlyingNoGravityPlayerInsideABorderIsRecorded() {
        player.canFly = true;
        player.isFlying = true;
        player.flySpeed = 0.05F;
        player.gamemode = GameMode.SURVIVAL;
        player.serverGamemode = GameMode.CREATIVE;
        PacketEntitySelf self = player.compensatedEntities.getSelf();
        self.hasGravity = false;
        self.potionsMap = new HashMap<>();
        self.potionsMap.put(MovementEffect.LEVITATION, 1);
        self.potionsMap.put(MovementEffect.BLINDNESS, 0);
        player.lastTransactionSent.set(120);
        player.lastTransactionReceived.set(118);
        PacketWorldBorder border = Mockito.mock(PacketWorldBorder.class);
        Mockito.when(border.getCenterX()).thenReturn(100.5);
        Mockito.when(border.getCenterZ()).thenReturn(-20.5);
        Mockito.when(border.getCurrentDiameter()).thenReturn(60.0);
        Mockito.when(border.getTargetDiameter()).thenReturn(30.0);
        Mockito.when(border.getRemainingLerpTicks()).thenReturn(40L);
        Mockito.when(border.getAbsoluteMaxSize()).thenReturn(29_999_984.0);

        List<String> lines = List.of(EngineStateSnapshot.render(player, border, null).split("\n"));

        assertEquals(10, lines.size(), String.join("\n", lines));
        assertEquals(
                "state abilities mayfly true flying true fly-speed 0.05 invulnerable false instabuild false",
                lines.get(0));
        assertEquals("state gamemode applied SURVIVAL sent CREATIVE", lines.get(1));
        assertTrue(lines.get(2).startsWith("state self no-gravity true pose STANDING fall-flying false"), lines.get(2));
        assertTrue(lines.get(3).startsWith("state attributes gravity 0.08 "), lines.get(3));
        assertEquals("state effects levitation 1", lines.get(4));
        assertEquals("state vehicle riding none passengers none", lines.get(8));
        assertEquals("state camera self", lines.get(9));
        assertEquals(
                "state border center 100.5 -20.5 size 60.0 target 30.0 remaining 40t absolute-max 2.9999984E7",
                lines.get(5));
        assertEquals("state transactions sent 120 received 118", lines.get(7));
    }

    @Test
    void theRealBorderAndAbilitiesAreRead() {
        String state = EngineStateSnapshot.render(player);
        assertTrue(state.contains("state abilities mayfly false sent-mayfly false "), state);
        assertTrue(state.contains("\nstate border center 0.0 0.0 size 5.9999968E7 target 5.9999968E7 "), state);
    }

    @Test
    void aTopicThatCannotBeReadCostsOnlyItsLine() {
        CompensatedEntities entities = player.compensatedEntities;
        player.compensatedEntities = null;
        String state;
        try {
            state = EngineStateSnapshot.render(player, null, null);
        } finally {
            player.compensatedEntities = entities;
        }
        assertTrue(state.contains("\nstate self unreadable\n"), state);
        assertTrue(state.contains("\nstate border unreadable\n"), state);
        assertTrue(state.startsWith("state abilities mayfly "), state);
        assertTrue(state.contains("\nstate transactions sent 0 received 0\n"), state);
    }

    @Test
    void theHeaderKeepsItsFirstLineAndBoundsTheStateApart() {
        String header = PredictionDiagnostics.withState("agent a, brand evil\nstate forged", "x".repeat(5000));
        assertEquals("agent a, brand evil state forged", header.split("\n")[0]);
        String bounded = DiagCuts.boundHeader("é".repeat(1000) + header.substring(header.indexOf('\n')));
        String[] lines = bounded.split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].getBytes(StandardCharsets.UTF_8).length <= DiagCuts.MAX_HEADER_BYTES);
        assertEquals(EngineStateSnapshot.MAX_BYTES, lines[1].length());
    }
}
