package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static ac.cult.cultac.network.JavaTickFixture.setField;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A wind charge lifts a resting boat and pushes it back into scaffolding (CultAC issue #190), shifted by
 * (-198, 2, -208) onto the fixture's floor. The boat's own Entity#move sees no scaffolding below its top, but
 * AbstractBoat#getGroundFriction reads BlockState#getCollisionShape(level, pos), whose CollisionContext.empty()
 * gives scaffolding its stable shape. Once the boat's bottom slab reaches the corner posts it is ON_LAND, so
 * AbstractBoat#floatBoat scales its velocity by scaffolding's 0.6 friction, not the air's 0.9.
 */
class JavaBoatScaffoldingFrictionTest {
    private static final int BOAT = 100;
    private static final Vec3 SHIFT = new Vec3(-198, 2, -208);
    /** The boat's reported yaw. */
    private static final float YAW = -4.922943F;

    private static final float PITCH = 41.890533F;
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, true);
    /** The boat at rest on the floor, one block south of the scaffolding's southern posts. */
    private static final Vec3 REST = new Vec3(198.38559160222653, 62.0, 208.45896332963355);
    /** The velocity the wind charge left the boat with. */
    private static final Vec3 PUSH = new Vec3(-0.09735701641945915, 0.12616736861380695, -0.5191356894341695);
    /** Lifted off the floor on land friction, then slowed by the posts it now sits among. */
    private static final Vec3[] MOVES = {
        new Vec3(198.3271773958566, 62.08616736861381, 208.14748193453877),
        new Vec3(198.29212887064196, 62.132334737227616, 207.96059309005562),
    };

    @ParameterizedTest(name = "{0}: scaffolding posts below the top are land to a boat")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aBoatAmongScaffoldingPostsUsesTheirLandFriction(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            scaffolding(f);
            Vec3 rest = REST.add(SHIFT);
            f.place(rest, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, rest, YAW, 0, 0);
            f.setPassengers(BOAT, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(PUSH);
            f.input(NONE);

            for (int tick = 0; tick < MOVES.length; tick++) {
                assertVehicleExact(ride(f, MOVES[tick].add(SHIFT)), tick);
            }
        }
    }

    /** The reported row of resting scaffolding (distance 0, not a bottom piece), at the boat's level. */
    private static void scaffolding(JavaTickFixture f) {
        var registry = DataTables.defaults().registry();
        int state = registry.with(BlockIds.SCAFFOLDING.defaultState(), "distance", "0");
        state = registry.with(state, "bottom", "false");
        for (int x = -2; x <= 2; x++) {
            for (int z = -3; z <= -1; z++) f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, z), state);
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    /** One client tick: the rider's Rot, the boat's move and the tick end. */
    private static PredictionResult ride(JavaTickFixture f, Vec3 to) {
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        var rot = new ServerboundMovePlayer(0, 0, 0, YAW, PITCH, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        var move = new ServerboundMoveVehicle(new Vec3d(to.x, to.y, to.z), YAW, PITCH, false, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, move), f.player, move);
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertTrue(result != null && result != before, "the vehicle move was not predicted");
        return result;
    }
}
