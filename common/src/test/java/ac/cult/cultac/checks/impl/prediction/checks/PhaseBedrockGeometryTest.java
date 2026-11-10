package ac.cult.cultac.checks.impl.prediction.checks;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.bedrock.player.BedrockPlayerState;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.events.packets.PacketWorldBorder;
import ac.cult.cultac.manager.player.SetbackFixtures;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundInitializeBorder;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetBorderCenter;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetBorderLerpSize;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetBorderSize;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Phase judges a Bedrock move against the geometry the Bedrock client has. The positions are reports from runs in
 * which the engine predicted every tick exactly while Phase set the player back; the world around them is a
 * controlled fixture, not a replay.
 */
class PhaseBedrockGeometryTest {
    private CultPlayer player;
    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        channel = new EmbeddedChannel();
        player = new CultPlayer(TestUsers.create(new User.Profile(UUID.randomUUID(), "Bedrock_Phase"), channel));
        Field version = CultPlayer.class.getDeclaredField("resolvedClientVersion");
        version.setAccessible(true);
        version.set(player, ClientVersion.V_26_3);
        player.gamemode = GameMode.SURVIVAL;
        player.movementPlatform = MovementPlatform.BEDROCK;
        player.bedrockState = new BedrockPlayerState(player.playerUUID);
        SetbackFixtures.setLoaded(player.getSetbackTeleportUtil(), true);
        player.checkManager.getSimulationProcessor().handleRespawn();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                player.compensatedWorld.ensureValidationChunkLoaded((20062 >> 4) + x, (20054 >> 4) + z);
                player.compensatedWorld.ensureValidationChunkLoaded((20084 >> 4) + x, (20085 >> 4) + z);
            }
        }
    }

    @AfterEach
    void close() {
        player.onRemove();
        channel.runPendingTasks();
        channel.finishAndReleaseAll();
    }

    @Test
    void aQuarterScalePlayerBesideAWallIsNotInsideIt() {
        // The client stops with its centre 0.075 from a wall at scale 0.25; these two reports are 0.25 from it.
        stone(20062, 121, 20054);
        scale(0.25F);
        RecordingPhase phase = new RecordingPhase(player);
        move(phase, new Vec3(20061.50213, 121.24918, 20054.43369));
        move(phase, new Vec3(20061.75248, 121.25220, 20054.65326));
        assertEquals(0, phase.flags);
        assertEquals(0.6F * 0.25F - 0.002, phase.oldBox.maxX - phase.oldBox.minX, 1.0E-9);
        assertEquals(1.8F * 0.25F - 0.002, phase.oldBox.maxY - phase.oldBox.minY, 1.0E-9);

        // Its box still cannot enter the wall: 0.075 + 0.006 past the closest legal centre.
        move(phase, new Vec3(20061.931, 121.25220, 20054.65326));
        assertEquals(1, phase.flags);
    }

    @Test
    void anUnscaledPlayerAtTheSameReportIsStillInsideTheWall() {
        stone(20062, 121, 20054);
        RecordingPhase phase = new RecordingPhase(player);
        move(phase, new Vec3(20061.50213, 121.24918, 20054.43369));
        move(phase, new Vec3(20061.75248, 121.25220, 20054.65326));
        assertEquals(1, phase.flags);
    }

    @Test
    void growingBesideAWallIsNotANewEntryButMovingDeeperIs() {
        stone(20062, 121, 20054);
        stone(20063, 121, 20054);
        scale(0.25F);
        RecordingPhase phase = new RecordingPhase(player);
        Vec3 beside = new Vec3(20061.9, 121.0, 20054.5);
        move(phase, beside);

        // The scale arrives between two ticks: the box grows in place and now overlaps the wall it stood beside.
        scale(1.5F);
        move(phase, beside);
        assertEquals(0, phase.flags);
        assertEquals(0.6F * 1.5F - 0.002, phase.oldBox.maxX - phase.oldBox.minX, 1.0E-9);

        // The grown box overlapped only the first block: reaching the second one is a new entry.
        move(phase, new Vec3(20062.6, 121.0, 20054.5));
        assertEquals(1, phase.flags);
    }

    @Test
    void shrinkingDoesNotKeepTheOverlapOfTheLargerBox() {
        stone(20062, 121, 20054);
        scale(2.0F);
        RecordingPhase phase = new RecordingPhase(player);
        move(phase, new Vec3(20061.3, 121.0, 20054.5));
        // 0.6 from the wall is the closest a doubled player stands; 0.02 from it at scale 0.25 is inside it.
        move(phase, new Vec3(20061.4, 121.0, 20054.5));
        assertEquals(0, phase.flags);
        scale(0.25F);
        move(phase, new Vec3(20061.98, 121.0, 20054.5));
        assertEquals(1, phase.flags);
    }

    @Test
    void aBedrockPlayerNeverTakesTheJavaBorder() {
        // A border of size 3 put Java's plane at x=20084. The client reported positions through it while the engine,
        // which has no such plane, predicted them exactly: the Bedrock client is never given this border.
        PacketWorldBorder border = player.checkManager.getWorldBorder();
        double unset = border.getCurrentDiameter();
        border.onInitializeBorder(null, player, new ClientboundInitializeBorder(20085.5, 20085.5, 3, 3, 0, 29999984));
        border.onSetBorderCenter(null, player, new ClientboundSetBorderCenter(20085.5, 20085.5));
        border.onSetBorderSize(null, player, new ClientboundSetBorderSize(3));
        border.onSetBorderLerpSize(null, player, new ClientboundSetBorderLerpSize(3, 16, 120));
        assertEquals(unset, border.getCurrentDiameter());
        assertEquals(0.0, border.getCenterX());
        assertTrue(border.hasClockIndependentDiameter());

        RecordingPhase phase = new RecordingPhase(player);
        move(phase, new Vec3(20084.5, 120.0, 20085.5));
        move(phase, new Vec3(20083.9, 120.0, 20085.5));
        assertEquals(0, phase.flags);

        SimpleCollisionBox query = GetBoundingBox.getBoundingBoxFromPosAndSize(20083.9, 120.0, 20085.5, 0.6F, 1.8F);
        player.lastX = 20084.5;
        player.lastZ = 20085.5;
        var boxes = new ArrayList<SimpleCollisionBox>();
        Collisions.getCollisionBoxes(player, query, boxes, false);
        assertTrue(boxes.isEmpty());
    }

    /** The scale the client acknowledged in its own entity data. */
    private void scale(float scale) {
        player.compensatedEntities.getSelf().onBedrockScale(scale);
    }

    private void stone(int x, int y, int z) {
        player.compensatedWorld.applyBlockChangeRawDANGER(
                x, y, z, DataTables.defaults().registry().block("minecraft:stone_bricks").defaultState());
    }

    /** One accepted Bedrock prediction ending at the client's reported position. */
    private void move(RecordingPhase phase, Vec3 end) {
        assertFalse(player.getSetbackTeleportUtil().shouldBlockMovement());
        assertFalse(player.checkManager.getSimulationProcessor().isExempt());
        SimulationContext context = mock(SimulationContext.class);
        when(context.hasTrustedAuthoredInput()).thenReturn(true);
        when(context.getBedrockInput())
                .thenReturn(BedrockAuthInputFrame.builder(player.playerUUID).build());
        when(context.getEnd()).thenReturn(end);
        PredictionResult result = mock(PredictionResult.class);
        when(result.getSimulationContext()).thenReturn(context);
        phase.onPredictionComplete(new PredictionComplete(result));
    }

    /** Retain the real flag decision while collecting it before alert/setback side effects. */
    private static final class RecordingPhase extends Phase {
        private int flags;

        private RecordingPhase(CultPlayer player) {
            super(player);
        }

        @Override
        public boolean flag(String verbose) {
            flags++;
            return false;
        }
    }
}
