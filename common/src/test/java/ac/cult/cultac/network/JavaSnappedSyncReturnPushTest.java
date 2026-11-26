package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityPositionSync;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerInfoUpdate;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PositionPath;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntityPlayer;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Seqs 52330 to 52366 of a capture, 37 blocks lower: a remote player lands beside the teleported player, a position sync
 * snaps it 73 blocks away and its interpolation, blocked from following, returns it to where it landed. The
 * rotation-only move after that interpolates from InterpolationHandler#position, where it stands, not from the codec
 * base the snap moved, so it pushes the player until the next far sync.
 */
class JavaSnappedSyncReturnPushTest {
    private static final int ID = 13901;
    private static final double DY = -37.0;
    /** seq 52330 entity_position_sync. */
    private static final Vec3 SYNCED = new Vec3(52.63649719895425, 117.625 + DY, 5.684958820197468);
    /** seqs 52332 to 52358 move_entity_pos deltas, in 1/4096 blocks. */
    private static final int[][] FALL = {
        {-3460, 5005, -1577}, {-765, 964, 0}, {-697, 624, 0}, {-634, 290, 0}, {-577, -37, 0}, {-526, -357, 0},
        {-477, -671, 0}, {-435, -979, 0}, {-396, -1280, 0}, {-360, -1576, 0}, {-328, -1866, 0}, {-298, -2149, 0},
        {-271, -2428, 0}, {-247, -2700, 0}, {-225, -2967, 0}, {-204, -3229, 0}, {-187, -3486, 0}, {-169, -3736, 0},
        {-154, -3984, 0}, {-140, -4225, 0}, {-128, -4461, 0}, {-116, -4693, 0}, {-106, -4921, 0}, {-96, -5143, 0},
        {-87, -5362, 0}, {-80, -5575, 0}, {-72, -5785, 0}
    };
    /** seq 52359 entity_position_sync: the landing beside the player. */
    private static final Vec3 LANDED = new Vec3(49.87736998443922, 101.0 + DY, 5.300000011920929);
    /** seqs 52360, 52363 and 52365 entity_position_sync, 73 blocks from {@link #LANDED}. */
    private static final Vec3 FAR = new Vec3(-10.700115380945475, 124.0 + DY, 39.641643899144334);
    /** seq 52362 accept_teleportation 59 at the player_position target. */
    private static final Vec3 RESPAWNED = new Vec3(49.86271972908602, FLOOR_Y, 5.300000011920929);
    /** seqs 52364, 52365 and 52366 move_player_pos_rot x. */
    private static final double[] DRIFT = {49.8566678170349, 49.84616934867633, 49.84043718428674};

    @Test
    void theRemotePlayerReturnedToItsLandingPushesUntilTheNextFarSync() throws Exception {
        try (var s = new Scenario()) {
            assertExact(s.tick(52364, DRIFT[0]));
            assertExact(s.tick(52365, DRIFT[1]));
            assertExact(s.tick(52366, DRIFT[2]));
            assertEquals(FAR.x, s.f.player.compensatedEntities.getEntity(ID).clientPhysicalPosition.x, 1.0E-9);
        }
    }

    @Test
    void aStepTwiceTheReturnedPlayersPushStillFlags() throws Exception {
        try (var s = new Scenario()) {
            assertExact(s.tick(52364, DRIFT[0]));
            PredictionResult result = s.tick(52365, DRIFT[1] + (DRIFT[1] - DRIFT[0]));
            assertFalse(exact(result), describe(result));
        }
    }

    @Test
    void aPushAfterTheNextFarSyncStillFlags() throws Exception {
        try (var s = new Scenario()) {
            assertExact(s.tick(52364, DRIFT[0]));
            assertExact(s.tick(52365, DRIFT[1]));
            // seq 52366 is friction alone: the remote player stands at the far sync by then.
            PredictionResult result = s.tick(52366, DRIFT[2] - (DRIFT[0] - RESPAWNED.x));
            assertFalse(exact(result), describe(result));
        }
    }

    /**
     * The remote player's packets of seq s handled before client tick s + 2, as the capture's pongs place them, and the
     * player respawned at its teleport target for the rotation-only tick 52363.
     */
    private static final class Scenario implements AutoCloseable {
        final JavaTickFixture f;
        private final Map<Integer, Runnable> sent = new HashMap<>();

        Scenario() throws Exception {
            f = new JavaTickFixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11);
            var world = f.player.compensatedWorld;
            world.ensureValidationChunkLoaded(3, 0);
            world.ensureValidationChunkLoaded(-1, 2);
            for (int x = 45; x <= 55; x++) {
                for (int z = 0; z <= 10; z++) {
                    world.updateBlock(new BlockPos(x, FLOOR_Y - 1, z), BlockIds.STONE.defaultState());
                }
            }
            // The far sync stands on ground (onGround at seq 52360).
            for (int x = -14; x <= -7; x++) {
                for (int z = 36; z <= 43; z++) {
                    world.updateBlock(new BlockPos(x, (int) FAR.y - 1, z), BlockIds.STONE.defaultState());
                }
            }
            for (int tick = 0; tick < 3; tick++) world.removeInvalidPistonLikeStuff();
            Vec3 away = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(away, Vec3.ZERO);
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            // It ticks after the player: seq 52363 sends no position, and its push first moves the player at 52364.
            addRemotePlayer(f, SYNCED);

            sent.put(52330, () -> sync(f, SYNCED, 110.65782165527344F, 90.0F, false));
            for (int i = 0; i < FALL.length; i++) {
                int[] delta = FALL[i];
                sent.put(52332 + i, () -> move(f, delta[0], delta[1], delta[2]));
            }
            sent.put(52359, () -> sync(f, LANDED, 117.85771179199219F, 89.54999542236328F, true));
            sent.put(52360, () -> sync(f, FAR, -91.924072265625F, 0.0F, true));
            sent.put(52362, () -> rotate(f, 70, 61, true));
            sent.put(52363, () -> sync(f, FAR, -91.924072265625F, 0.0F, false));
            sent.put(52364, () -> rotate(f, -79, -3, false));
            sent.put(52365, () -> sync(f, FAR, -150.57408142089844F, -18.750001907348633F, true));
            for (int tick = 52332; tick <= 52362; tick++) {
                handleBefore(tick);
                assertExact(f.tick(away, true));
            }
            handleBefore(52363);
            f.place(RESPAWNED, Vec3.ZERO);
            f.rideTick();
        }

        PredictionResult tick(int seq, double x) {
            handleBefore(seq);
            return f.tick(new Vec3(x, FLOOR_Y, RESPAWNED.z), true);
        }

        private void handleBefore(int tick) {
            Runnable handled = sent.get(tick - 2);
            if (handled != null) handled.run();
        }

        @Override
        public void close() throws Exception {
            f.close();
        }
    }

    private static void addRemotePlayer(JavaTickFixture f, Vec3 at) {
        f.player.packetEntityReplication.addEntity(ID, EntityTypeIds.PLAYER, at, 0, 0, 0, -1);
        f.pongThrough(f.player.lastTransactionSent.get());
        UUID profile = UUID.randomUUID();
        var info = new ClientboundPlayerInfoUpdate(
                EnumSet.of(
                        ClientboundPlayerInfoUpdate.Action.ADD_PLAYER,
                        ClientboundPlayerInfoUpdate.Action.UPDATE_GAME_MODE),
                List.of(new ClientboundPlayerInfoUpdate.Entry(profile, GameMode.SURVIVAL, Map.of())));
        CompensatedEntities entities = f.player.compensatedEntities;
        entities.clientPlayerModes.update(info, Set.of(profile), entities.entityMap.values());
        ((PacketEntityPlayer) entities.getEntity(ID)).profile = profile;
    }

    private static void sync(JavaTickFixture f, Vec3 at, float yaw, float pitch, boolean onGround) {
        var sync = new ClientboundEntityPositionSync(
                ID, new PositionPath.Linear(new Vec3d(at.x, at.y, at.z)), yaw, pitch, onGround);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_POSITION_SYNC, sync, false);
        f.player.packetEntityReplication.onEntityPositionSync(event, f.player, sync);
        handled(f, event);
    }

    private static void move(JavaTickFixture f, int dx, int dy, int dz) {
        send(f, new ClientboundMoveEntity(
                ID, new EntityDelta.Linear((short) dx, (short) dy, (short) dz), 0.0F, 0.0F, false, true, false));
    }

    /** A move_entity_rot, its angles in the wire's 1/256 turns. */
    private static void rotate(JavaTickFixture f, int yaw, int pitch, boolean onGround) {
        send(f, new ClientboundMoveEntity(
                ID, EntityDelta.ZERO, yaw * 360.0F / 256.0F, pitch * 360.0F / 256.0F, onGround, false, true));
    }

    private static void send(JavaTickFixture f, ClientboundMoveEntity move) {
        var event =
                new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, move, false);
        f.player.packetEntityReplication.onMoveEntity(event, f.player, move);
        handled(f, event);
    }

    private static void handled(JavaTickFixture f, PacketSendEvent<?> event) {
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
