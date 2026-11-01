package ac.cult.cultac.checks.impl.prediction.stage.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * LocalPlayer#aiStep probes at getBbWidth() * 0.35 before travel. These component fixtures use
 * recorded positions and a retained wall witness, rather than a reconstructed session replay.
 */
class ScaledEscapeProbeTest {
    private static final Vec3 START = new Vec3(11026.118120771249, 62, 1016.5200952818748);
    private CultPlayer player;
    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        channel = new EmbeddedChannel();
        player = new CultPlayer(TestUsers.create(new User.Profile(UUID.randomUUID(), "Escape_Test"), channel));
        Field version = CultPlayer.class.getDeclaredField("resolvedClientVersion");
        version.setAccessible(true);
        version.set(player, ClientVersion.V_1_21_11);
        player.gamemode = GameMode.SURVIVAL;
        player.getSetbackTeleportUtil().hasFullyLoaded = true;
        player.checkManager.getSimulationProcessor().handleRespawn();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                player.compensatedWorld.ensureValidationChunkLoaded(x, z);
                player.compensatedWorld.ensureValidationChunkLoaded((11026 >> 4) + x, (1016 >> 4) + z);
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
    void capturedEscapeColumnsAreDetectedUntilThePlayerClearsTheWall() throws Exception {
        // This full block witnesses the northward escape on captured ticks 541 and 542.
        stone(11026, 62, 1017);
        Vec3[] starts = {
            START,
            new Vec3(11026.102072339732, 62, 1016.4088432661974),
            new Vec3(11026.075954670201, 62, 1016.2929814534372)
        };
        for (int tick = 0; tick < starts.length; tick++) {
            at(starts[tick], 3);
            assertEquals(tick < 2, escape(starts[tick], Pose.STANDING, 3));
        }
    }

    @Test
    void probesUseContextDimensionsRatherThanTheLatestAttribute() throws Exception {
        stone(11026, 62, 1017);
        at(START, 3);
        player.compensatedEntities.getSelf().scale = 1;
        assertTrue(escape(START, Pose.STANDING, 3));
        at(START, 1);
        player.compensatedEntities.getSelf().scale = 3;
        assertFalse(escape(START, Pose.STANDING, 1));
    }

    @Test
    void smallActorsAndNarrowPosesDoNotAcquireDistantEscapeMotion() throws Exception {
        stone(11026, 62, 1017);
        for (float scale : new float[] {0.5F, 1}) {
            at(START, scale);
            assertFalse(escape(START, Pose.STANDING, scale));
        }
        at(START, 3);
        assertFalse(escape(START, Pose.SLEEPING, 3));
    }

    @Test
    void aSealedCavityHasNoEscape() throws Exception {
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) stone(x, 64, z);
        }
        Vec3 position = new Vec3(0.5, 64, 0.5);
        at(position, 3);
        assertFalse(escape(position, Pose.STANDING, 3));
    }

    private void stone(int x, int y, int z) {
        player.compensatedWorld.applyBlockChangeRawDANGER(
                x,
                y,
                z,
                DataTables.defaults().registry().block("minecraft:stone_bricks").defaultState());
    }

    private void at(Vec3 position, float scale) {
        player.x = player.lastX = position.x;
        player.y = player.lastY = position.y;
        player.z = player.lastZ = position.z;
        player.compensatedEntities.getSelf().scale = scale;
        player.dimensionScale = scale;
        player.boundingBox = GetBoundingBox.getBoundingBoxFromPosAndSize(
                position.x, position.y, position.z, 0.6F * scale, 1.8F * scale);
    }

    private boolean escape(Vec3 start, Pose pose, float scale) throws Exception {
        SimulationContext context = mock(SimulationContext.class);
        when(context.getStart()).thenReturn(start);
        when(context.getPose()).thenReturn(pose);
        when(context.getScale()).thenReturn(scale);
        Method method = WorldStageBuilder.class.getDeclaredMethod(
                "mightBeSuffocating", CultPlayer.class, SimulationContext.class);
        method.setAccessible(true);
        return (boolean) method.invoke(new WorldStageBuilder(), player, context);
    }
}
