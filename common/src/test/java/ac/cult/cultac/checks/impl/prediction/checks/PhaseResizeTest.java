package ac.cult.cultac.checks.impl.prediction.checks;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Component regressions for the physical start and Entity#refreshDimensions resizing the box before travel.
 * Captured positions and retained wall witnesses are used below; the remaining world is a
 * controlled empty fixture, not a reconstructed session or complete capture replay.
 */
class PhaseResizeTest {
    private static final Vec3 BEFORE_GROWTH = new Vec3(11026.118120771249, 62, 1016.5200952818748);
    private CultPlayer player;
    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        channel = new EmbeddedChannel();
        player = new CultPlayer(TestUsers.create(new User.Profile(UUID.randomUUID(), "Scale_Test"), channel));
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
    void capturedGrowthDoesNotTurnExistingOverlapIntoThreePenetrations() {
        // Full diagnostics 538-540 retain both of these stone-brick blocks.
        stone(11026, 63, 1017);
        stone(11027, 63, 1016);
        RecordingPhase phase = new RecordingPhase(player);
        phase.oldBox = GetBoundingBox.getBoundingBoxFromPosAndSize(
                BEFORE_GROWTH.x, BEFORE_GROWTH.y, BEFORE_GROWTH.z, 0.6F, 1.8F);
        at(BEFORE_GROWTH, 3);
        Vec3[] destinations = {
            new Vec3(11026.154059216833, 63.22000002861023, 1016.1733698051366),
            new Vec3(11026.167514154427, 63.60000038146973, 1015.9587997585594),
            new Vec3(11026.17803942666, 63.52160037994385, 1015.7375978846158)
        };
        for (Vec3 destination : destinations) {
            move(phase, destination);
            assertEquals(0, phase.flags);
            assertEquals(destination.x, (phase.oldBox.minX + phase.oldBox.maxX) / 2, 1e-10);
            assertEquals((double) (0.6F * 3), phase.oldBox.maxX - phase.oldBox.minX, 1e-10);
            at(destination, 3);
        }
    }

    @Test
    void growthDoesNotHideNewPenetrationInAnyHorizontalDirection() {
        stone(2, 64, 0);
        stone(-2, 64, 0);
        stone(0, 64, 2);
        stone(0, 64, -2);
        for (Vec3 destination : new Vec3[] {
            new Vec3(1.3, 64, 0.5), new Vec3(-0.3, 64, 0.5),
            new Vec3(0.5, 64, 1.3), new Vec3(0.5, 64, -0.3)
        }) {
            RecordingPhase phase = new RecordingPhase(player);
            phase.oldBox = GetBoundingBox.getBoundingBoxFromPosAndSize(0.5, 64, 0.5, 0.6F, 1.8F);
            at(new Vec3(0.5, 64, 0.5), 3);
            move(phase, destination);
            assertEquals(1, phase.flags, destination.toString());
        }
    }

    @Test
    void stableSizeStillRejectsNewPenetration() {
        stone(1, 64, 0);
        RecordingPhase phase = new RecordingPhase(player);
        phase.oldBox = GetBoundingBox.getBoundingBoxFromPosAndSize(0.5, 64, 0.5, 0.6F, 1.8F);
        at(new Vec3(0.5, 64, 0.5), 1);
        move(phase, new Vec3(0.8, 64, 0.5));
        assertEquals(1, phase.flags);
    }

    @Test
    void shrinkingCannotReuseOverlapFromTheLargerBox() {
        stone(1, 64, 0);
        RecordingPhase phase = new RecordingPhase(player);
        phase.oldBox = GetBoundingBox.getBoundingBoxFromPosAndSize(0.5, 64, 0.5, 0.6F * 3, 1.8F * 3);
        at(new Vec3(0.5, 64, 0.5), 1);
        move(phase, new Vec3(0.8, 64, 0.5));
        assertEquals(1, phase.flags);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void phaseUsesThePhysicalStartRatherThanThePreviousReport(String release, ClientVersion version) throws Exception {
        setClientVersion(version);
        player.compensatedWorld.ensureValidationChunkLoaded(332 >> 4, -45 >> 4);
        stone(333, 82, -45);
        RecordingPhase phase = new RecordingPhase(player);
        // Captured 1.21.2 report geometry. The fixture supplies a post-push physical
        // start already overlapping the now-static block; it is not a full capture replay.
        Vec3 previousReport = new Vec3(332.30000001192093, 82, -44.765468390198954);
        Vec3 physicalStart = new Vec3(332.7100000119209, 82, previousReport.z);
        phase.oldBox = GetBoundingBox.getBoundingBoxFromPosAndSize(
                previousReport.x, previousReport.y, previousReport.z, 0.6f, 1.8f);
        at(physicalStart, 1);
        completeFrom(phase, physicalStart);

        move(phase, physicalStart);

        assertEquals(0, phase.flags);
        assertEquals(physicalStart.x, (phase.oldBox.minX + phase.oldBox.maxX) / 2, 1e-10);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aPushDoesNotAllowEntryIntoAnotherStaticBlock(String release, ClientVersion version) throws Exception {
        setClientVersion(version);
        stone(1, 64, 0);
        stone(2, 64, 0);
        RecordingPhase phase = new RecordingPhase(player);
        phase.oldBox = GetBoundingBox.getBoundingBoxFromPosAndSize(0.5, 64, 0.5, 0.6f, 1.8f);
        Vec3 physicalStart = new Vec3(0.71, 64, 0.5);
        at(physicalStart, 1);
        completeFrom(phase, physicalStart);

        move(phase, new Vec3(1.71, 64, 0.5));

        assertEquals(1, phase.flags);
    }

    private void setClientVersion(ClientVersion version) throws Exception {
        Field field = CultPlayer.class.getDeclaredField("resolvedClientVersion");
        field.setAccessible(true);
        field.set(player, version);
    }

    private void completeFrom(RecordingPhase phase, Vec3 physicalStart) {
        // Component boundary: supply the runner's post-push start; this does not replay a piston.
        SimulationContext context = mock(SimulationContext.class);
        when(context.getStart()).thenReturn(physicalStart);
        PredictionResult result = mock(PredictionResult.class);
        when(result.getSimulationContext()).thenReturn(context);
        phase.onPredictionComplete(new PredictionComplete(result));
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

    private void move(RecordingPhase phase, Vec3 position) {
        assertFalse(player.getSetbackTeleportUtil().shouldBlockMovement());
        assertFalse(player.checkManager.getSimulationProcessor().isExempt());
        phase.onMovePlayer(
                null,
                player,
                new ServerboundMovePlayer(position.x, position.y, position.z, 0, 0, false, false, true, false));
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
