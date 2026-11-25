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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AbstractBoat#floatBoat lands a boat falling into water with setPos on the water's surface and zeroes its
 * deltaMovement.y, so the next tick floats from rest by the buoyancy of the water level AbstractBoat#checkInWater
 * found. Heights come from a boat driven off a ledge into a source block's 8/9 surface; here the ledge turns to water
 * under the resting boat.
 */
class BoatWaterEntryTest {
    private static final int BOAT = 100;
    private static final int WATER_Y = FLOOR_Y - 2;
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final int RESTING_TICKS = 10;
    private static final double[] HEIGHTS = {
        62.96, 62.88, 62.5385, 62.53724989232772, 62.53516477683845, 62.53262449188556, 62.5299512634721
    };

    @Test
    @DisplayName("a boat that lands on water floats from rest")
    void aBoatThatLandsOnWaterFloatsFromRest() throws Exception {
        try (var f = mount()) {
            for (int tick = 0; tick < HEIGHTS.length; tick++) {
                assertVehicleExact(f.rideTick(at(HEIGHTS[tick]), false), tick);
            }
        }
    }

    @Test
    @DisplayName("a boat that keeps falling after it lands on water is rejected")
    void aBoatThatKeepsFallingAfterItLandsIsRejected() throws Exception {
        try (var f = mount()) {
            for (int tick = 0; tick < 3; tick++) {
                assertVehicleExact(f.rideTick(at(HEIGHTS[tick]), false), tick);
            }
            assertRejected(f.rideTick(at(HEIGHTS[2] - 0.2573751076443361), false), "VehicleOffset");
        }
    }

    private static Vec3 at(double y) {
        return new Vec3(0.5, y - 62 + WATER_Y, 0.5);
    }

    private static JavaTickFixture mount() throws Exception {
        var f = new JavaTickFixture();
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y - 1, z), BlockIds.AIR.defaultState());
                f.player.compensatedWorld.updateBlock(new BlockPos(x, WATER_Y, z), BlockIds.STONE.defaultState());
                f.player.compensatedWorld.updateBlock(new BlockPos(x, WATER_Y - 1, z), BlockIds.STONE.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
        Vec3 seat = at(63.0);
        f.place(seat, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, seat, 0, 0, 0);
        f.setPassengers(BOAT, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.player.xRot = 0;
        f.player.yRot = 0;
        f.input(NONE);
        f.tickEnd();
        for (int tick = 0; tick < RESTING_TICKS; tick++) assertVehicleExact(f.rideTick(seat, true), tick);
        f.water(new BlockPos(-4, WATER_Y, -4), new BlockPos(4, WATER_Y, 4));
        return f;
    }
}
