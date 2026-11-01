package ac.cult.cultac.checks.impl.prediction.checks;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Component regressions for Entity#refreshDimensions resizing the client box before travel.
 * Captured positions and two retained wall witnesses are used below; the remaining world is a
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
