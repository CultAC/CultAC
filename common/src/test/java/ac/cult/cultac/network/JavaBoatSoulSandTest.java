package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static ac.cult.cultac.network.JavaTickFixture.setField;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
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
 * A client drives a boat forward from rest on soul sand (CultAC issue #189), shifted by (-263, 2, -131) onto the
 * fixture's floor. Entity#move ends the boat's move by scaling deltaMovement by getBlockSpeedFactor, 0.4 on soul
 * sand, so the second move is 0.04 * 0.4 * 0.6 + 0.04: the speed factor, then AbstractBoat#floatBoat's land friction,
 * then AbstractBoat#controlBoat's thrust.
 */
class JavaBoatSoulSandTest {
    private static final int BOAT = 100;
    private static final Vec3 SHIFT = new Vec3(-263, 2, -131);
    // The client reported one rotation for both the rider and the boat.
    private static final float YAW = 2.058166F;
    private static final float PITCH = 46.371056F;
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, true);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    /** The boat at rest on the soul sand. */
    private static final Vec3 REST = new Vec3(263.0687118335067, 61.875, 131.26628780520932);
    /** The boat's reported moves once forward reached it: thrust from rest, then thrust over the slowed velocity. */
    private static final Vec3[] MOVES = {
        new Vec3(263.0672778689208, 61.875, 131.3062620923616), new Vec3(263.0654997528155, 61.875, 131.35583020895464),
    };

    @ParameterizedTest(name = "{0}: soul sand's speed factor slows the boat's carried velocity")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aBoatOnSoulSandCarriesItsSpeedFactor(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    f.player.compensatedWorld.updateBlock(
                            new BlockPos(x, FLOOR_Y - 1, z), BlockIds.SOUL_SAND.defaultState());
                }
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 rest = REST.add(SHIFT);
            f.place(rest, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, rest, YAW, 0, 0);
            f.setPassengers(BOAT, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.input(NONE);
            assertVehicleExact(ride(f, rest), 0);
            // ClientLevel ticks the boat before LocalPlayer#rideTick hands it the newly pressed key.
            f.input(FORWARD);
            assertVehicleExact(ride(f, rest), 1);

            for (int tick = 0; tick < MOVES.length; tick++) {
                assertVehicleExact(ride(f, MOVES[tick].add(SHIFT)), tick + 2);
            }
        }
    }

    /** One client tick: the rider's Rot, the boat's move and the tick end. */
    private static PredictionResult ride(JavaTickFixture f, Vec3 to) {
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        var rot = new ServerboundMovePlayer(0, 0, 0, YAW, PITCH, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        var move = new ServerboundMoveVehicle(new Vec3d(to.x, to.y, to.z), YAW, PITCH, true, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, move), f.player, move);
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertTrue(result != null && result != before, "the vehicle move was not predicted");
        return result;
    }
}
