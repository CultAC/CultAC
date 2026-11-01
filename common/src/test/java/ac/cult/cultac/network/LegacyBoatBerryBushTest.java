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
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A client before 1.21.11 drives a boat forward on land into sweet berry bushes (CultAC issue #127), shifted by
 * (-245, 2, -138) onto the fixture's floor. SweetBerryBushBlock#entityInside slows only a LivingEntity, so the boat
 * keeps its land friction and thrust inside the bushes.
 */
class LegacyBoatBerryBushTest {
    private static final int BOAT = 100;
    private static final Vec3 SHIFT = new Vec3(-245, 2, -138);
    private static final float PITCH = 33.173363F;
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);

    /** The reported boat's moves with forward held; the bushes overlap it from the fourth position. */
    private static final Vec3[] POSITIONS = {
        new Vec3(248.11101916007837, 62.0, 142.77998017546648),
        new Vec3(248.10527105618183, 62.0, 142.71622806060387),
        new Vec3(248.09822985552668, 62.0, 142.63813842768116),
        new Vec3(248.09041279720523, 62.0, 142.55144628823507),
        new Vec3(248.08213022431175, 62.0, 142.4595926451826),
        new Vec3(248.07356452333784, 62.0, 142.3646424466029),
        new Vec3(248.06482894552576, 62.0, 142.26783431481758),
        new Vec3(248.0559914410962, 62.0, 142.16991141740513),
        new Vec3(248.04709278069373, 62.0, 142.07131966058984),
    };
    /** The rider's reported yaw at each position. */
    private static final float[] RIDER_YAWS = {
        -185.37474F, -185.37833F, -185.38048F, -185.38176F, -185.38254F,
        -185.383F, -185.38327F, -185.38344F, -185.38354F,
    };
    /** The boat's yaw at each position: the direction of AbstractBoat#controlBoat's 0.04 thrust in each move. */
    private static final float[] BOAT_YAWS = {
        -185.1526F, -185.1526F, -185.1526F, -185.1526F, -185.1526F,
        -185.1581F, -185.1581F, -185.1581F, -185.1581F,
    };

    @ParameterizedTest
    @EnumSource(value = ClientVersion.class, names = {"V_1_21_4", "V_1_21_9"})
    void aBoatKeepsItsSpeedInsideSweetBerryBushes(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            for (int x = 247; x <= 248; x++) {
                for (int z = 140; z <= 141; z++) {
                    f.player.compensatedWorld.updateBlock(
                            new BlockPos(x + (int) SHIFT.x, FLOOR_Y, z + (int) SHIFT.z),
                            BlockIds.SWEET_BERRY_BUSH.defaultState());
                }
            }
            Vec3 start = POSITIONS[0].add(SHIFT);
            f.place(start, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, start, BOAT_YAWS[0], 0, 0);
            f.setPassengers(BOAT, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.input(FORWARD);
            f.tickEnd();
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(
                    POSITIONS[0].subtract(new Vec3(248.11461210279623, 62.0, 142.8198364278451)));

            for (int tick = 1; tick < POSITIONS.length; tick++) {
                assertVehicleExact(ride(f, POSITIONS[tick].add(SHIFT), RIDER_YAWS[tick], BOAT_YAWS[tick]), tick);
            }
        }
    }

    /** One client tick: the rider's Rot, the boat's move and the tick end. */
    private static PredictionResult ride(JavaTickFixture f, Vec3 to, float riderYaw, float boatYaw) {
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        var rot = new ServerboundMovePlayer(0, 0, 0, riderYaw, PITCH, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        var move = new ServerboundMoveVehicle(new Vec3d(to.x, to.y, to.z), boatYaw, 0, true, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, move), f.player, move);
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertTrue(result != null && result != before, "the vehicle move was not predicted");
        return result;
    }
}
