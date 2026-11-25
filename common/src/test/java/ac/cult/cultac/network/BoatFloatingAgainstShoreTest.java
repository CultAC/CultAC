package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.enums.BoatEntityStatus;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A boat floating near AbstractBoat#floatBoat's rest height on a source block's 8/9 surface moves by less than 1.0E-7.
 * floatBoat computes {@code ((dy - 0.04) + (waterLevel - y) / 0.5625F * (0.04 / 0.65)) * 0.75} in doubles.
 */
class BoatFloatingAgainstShoreTest {
    private static final int BOAT = 100;
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    /** Water at y 63: floatBoat from rest at REST_Y moves up by 1.1449174941446927E-16. */
    private static final double REST_Y = 63.52326431274414;

    private static final double REST_DY = 1.1449174941446927E-16;
    /** A boat 1.375 wide whose +z face touches the shore at z 2. */
    private static final double FLUSH_Z = 1.3125;
    /** The drift towards the shore after floatBoat's 0.9F water friction. */
    private static final double DRIFT_Z = 0.01 * 0.9F;

    @Test
    void aBoatAtRestFloatsByVanillasBuoyancy() throws Exception {
        try (var f = mount(FLOOR_Y - 1, REST_Y, false)) {
            PredictionResult result = f.rideTick(new Vec3(0.5, REST_Y, FLUSH_Z + DRIFT_Z), false);
            assertVehicleExact(result, 0);
            assertEquals(REST_DY, result.getValidMovements().getClosestTrace().preCollisionMovement().y, 0.0);
        }
    }

    private static JavaTickFixture mount(int waterY, double y, boolean shore) throws Exception {
        var f = new JavaTickFixture();
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                boolean wall = shore && z >= 2;
                for (int above = waterY + 1; above <= FLOOR_Y; above++) {
                    f.player.compensatedWorld.updateBlock(
                            new BlockPos(x, above, z),
                            wall ? BlockIds.STONE.defaultState() : BlockIds.AIR.defaultState());
                }
                f.player.compensatedWorld.updateBlock(
                        new BlockPos(x, waterY, z),
                        wall ? BlockIds.STONE.defaultState() : BlockIds.WATER.defaultState());
                f.player.compensatedWorld.updateBlock(new BlockPos(x, waterY - 1, z), BlockIds.STONE.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
        Vec3 seat = new Vec3(0.5, y, FLUSH_Z);
        f.place(seat, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, seat, 0, 0, 0);
        f.player.compensatedEntities.getEntity(BOAT).boatStatus = BoatEntityStatus.IN_WATER;
        f.setPassengers(BOAT, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.player.xRot = 0;
        f.player.yRot = 0;
        f.input(NONE);
        f.tickEnd();
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(new Vec3(0, 0, 0.01));
        return f;
    }
}
