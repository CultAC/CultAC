package ac.cult.cultac.checks.impl.prediction.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Entity#positionRider seats a rider with setPos, so a seat can overlap blocks, and client-side stopRiding leaves the
 * player on that seat. Positions are from a recorded session: the last move before mounting
 * a bat, the seat the engine committed at the dismount, the first move after it and the oak planks at (2, 109, 1).
 */
class PhaseDismountTest {
    private static final Vec3 BEFORE_MOUNT = new Vec3(0.12356410993447026, 109.24918707874468, -1.3135371993879588);
    private static final Vec3 SEAT = new Vec3(3.2568359375, 109.30805661678315, 0.8567708333333333);
    private static final Vec3 FIRST_MOVE = new Vec3(3.246835526409129, 109.30805661678315, 0.8807706577269647);
    private CultPlayer player;
    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        channel = new EmbeddedChannel();
        player = new CultPlayer(TestUsers.create(new User.Profile(UUID.randomUUID(), "Dismount_Phase"), channel));
        Field version = CultPlayer.class.getDeclaredField("resolvedClientVersion");
        version.setAccessible(true);
        version.set(player, ClientVersion.V_1_21_11);
        player.gamemode = GameMode.SURVIVAL;
        player.getSetbackTeleportUtil().hasFullyLoaded = true;
        player.checkManager.getSimulationProcessor().handleRespawn();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                player.compensatedWorld.ensureValidationChunkLoaded(x, z);
            }
        }
        planks(2, 109, 1);
    }

    @AfterEach
    void close() {
        player.onRemove();
        channel.runPendingTasks();
        channel.finishAndReleaseAll();
    }

    @Test
    void firstMoveOffASeatInsideABlockDoesNotFlag() throws Exception {
        RecordingPhase phase = mountedBeforeAt(BEFORE_MOUNT);
        rideTickSeat(SEAT);
        at(SEAT);
        move(phase, FIRST_MOVE);
        assertEquals(0, phase.flags);
    }

    @Test
    void rotationTicksAfterTheDismountKeepTheSeat() throws Exception {
        RecordingPhase phase = mountedBeforeAt(BEFORE_MOUNT);
        rideTickSeat(SEAT);
        at(SEAT);
        phase.onMovePlayer(null, player, new ServerboundMovePlayer(0, 0, 0, 66.2F, 58.5F, false, false, false, true));
        rideTickSeat(null);
        move(phase, FIRST_MOVE);
        assertEquals(0, phase.flags);
    }

    @Test
    void movingFromTheSeatIntoAnotherBlockStillFlags() throws Exception {
        planks(4, 109, 1);
        RecordingPhase phase = mountedBeforeAt(BEFORE_MOUNT);
        rideTickSeat(SEAT);
        at(SEAT);
        move(phase, new Vec3(3.75, SEAT.y, SEAT.z));
        assertEquals(1, phase.flags);
    }

    @Test
    void aSeatTheClientNeverRodeToDoesNotClearTheBlock() throws Exception {
        RecordingPhase phase = mountedBeforeAt(BEFORE_MOUNT);
        rideTickSeat(null);
        at(BEFORE_MOUNT);
        move(phase, FIRST_MOVE);
        assertEquals(1, phase.flags);
    }

    @Test
    void aMoveAfterTheDismountTeleportStartsFromItsDestination() throws Exception {
        RecordingPhase phase = mountedBeforeAt(BEFORE_MOUNT);
        rideTickSeat(SEAT);
        at(SEAT);
        teleportTo(phase, new Vec3(3.5, SEAT.y, 2.5));
        move(phase, FIRST_MOVE);
        assertEquals(1, phase.flags);
    }

    @Test
    void aDismountTeleportIntoABlockKeepsThatBlockClear() throws Exception {
        planks(5, 109, 5);
        RecordingPhase phase = mountedBeforeAt(BEFORE_MOUNT);
        rideTickSeat(SEAT);
        at(SEAT);
        Vec3 destination = new Vec3(5.5, SEAT.y, 5.5);
        teleportTo(phase, destination);
        move(phase, new Vec3(5.51, SEAT.y, 5.5));
        assertEquals(0, phase.flags);
    }

    private void teleportTo(RecordingPhase phase, Vec3 position) {
        player.packetStateData.lastPacketWasTeleport = true;
        phase.onMovePlayer(
                null,
                player,
                new ServerboundMovePlayer(position.x, position.y, position.z, 0, 0, false, false, true, true));
        player.packetStateData.lastPacketWasTeleport = false;
        at(position);
    }

    private RecordingPhase mountedBeforeAt(Vec3 position) {
        RecordingPhase phase = new RecordingPhase(player);
        phase.oldBox = GetBoundingBox.getBoundingBoxFromPosAndSize(position.x, position.y, position.z, 0.6F, 1.8F);
        return phase;
    }

    private void rideTickSeat(Vec3 seat) throws Exception {
        Field field = CompensatedEntities.class.getDeclaredField("rideTickSeat");
        field.setAccessible(true);
        field.set(player.compensatedEntities, seat);
    }

    private void planks(int x, int y, int z) {
        player.compensatedWorld.applyBlockChangeRawDANGER(
                x, y, z, DataTables.defaults().registry().block("minecraft:oak_planks").defaultState());
    }

    private void at(Vec3 position) {
        player.x = player.lastX = position.x;
        player.y = player.lastY = position.y;
        player.z = player.lastZ = position.z;
        player.boundingBox =
                GetBoundingBox.getBoundingBoxFromPosAndSize(position.x, position.y, position.z, 0.6F, 1.8F);
    }

    private void move(RecordingPhase phase, Vec3 position) {
        assertFalse(player.inVehicle());
        assertFalse(player.getSetbackTeleportUtil().shouldBlockMovement());
        assertFalse(player.checkManager.getSimulationProcessor().isExempt());
        phase.onMovePlayer(
                null,
                player,
                new ServerboundMovePlayer(position.x, position.y, position.z, 0, 0, false, false, true, false));
    }

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
