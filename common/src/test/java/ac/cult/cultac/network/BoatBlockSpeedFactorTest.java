package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Entity#move ends by scaling every entity's horizontal deltaMovement by Entity#getBlockSpeedFactor, a boat's
 * included. A boat driven forward on soul sand or honey carries 0.4 of each move into the next AbstractBoat#tick.
 */
class BoatBlockSpeedFactorTest {
    private static final int BOAT = 100;
    private static final int TICKS = 10;
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    // Block#getFriction of soul sand and honey, which AbstractBoat#getGroundFriction averages under the boat.
    private static final float LAND_FRICTION = 0.6F;
    private static final float SPEED_FACTOR = 0.4F;

    @ParameterizedTest
    @ValueSource(strings = {"soul_sand", "honey_block"})
    void aBoatDrivenOverASlowBlockCarriesItsSpeedFactor(String block) throws Exception {
        try (var f = mount(block)) {
            Vec3 position = seat(block);
            double carried = 0;
            for (int tick = 0; tick < TICKS; tick++) {
                double moved = forwardMove(carried);
                position = position.add(0, 0, moved);
                assertVehicleExact(f.rideTick(position, true), tick);
                carried = moved * SPEED_FACTOR;
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"soul_sand", "honey_block"})
    void aBoatOutpacingTheSpeedFactorIsRejected(String block) throws Exception {
        try (var f = mount(block)) {
            Vec3 position = seat(block);
            double moved = 0;
            // Six vanilla ticks driven before the faster one.
            for (int tick = 0; tick < 6; tick++) {
                moved = forwardMove(moved * SPEED_FACTOR);
                position = position.add(0, 0, moved);
                assertVehicleExact(f.rideTick(position, true), tick);
            }
            position = position.add(0, 0, forwardMove(moved));
            assertRejected(f.rideTick(position, true), "VehicleOffset");
        }
    }

    /** AbstractBoat#floatBoat on land, then AbstractBoat#controlBoat's 0.04 forward thrust at yaw 0. */
    private static double forwardMove(double carried) {
        return carried * LAND_FRICTION + 0.04F;
    }

    private static Vec3 seat(String block) {
        double top = block.equals("soul_sand") ? 14 / 16.0 : 15 / 16.0;
        return new Vec3(0.5, FLOOR_Y - 1 + top, 0.5);
    }

    private static JavaTickFixture mount(String block) throws Exception {
        var f = new JavaTickFixture();
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                f.player.compensatedWorld.updateBlock(
                        new BlockPos(x, FLOOR_Y - 1, z),
                        (block.equals("soul_sand") ? BlockIds.SOUL_SAND : BlockIds.HONEY_BLOCK).defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        Vec3 seat = seat(block);
        f.place(seat, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, seat, 0, 0, 0);
        f.setPassengers(BOAT, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.player.xRot = 0;
        f.player.yRot = 0;
        f.input(FORWARD);
        f.tickEnd();
        return f;
    }
}
