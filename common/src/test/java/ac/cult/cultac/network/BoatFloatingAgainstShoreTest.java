package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.OffsetHandler;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.VehicleOffset;
import ac.cult.cultac.checks.impl.vehicle.VehicleGround;
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
    /** Water at y 62: floatBoat from rest at SETTLED_Y moves down by -4.683753385137379E-16. */
    private static final double SETTLED_Y = 62.523264312744146;

    private static final int COW = 101;
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
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

    /**
     * A boat at rest flush with the shore and steered into it: the push makes BlockCollisions gather the shore on
     * every tick, so Shapes#collide clips floatBoat's fall to 0 and Entity#move reports ground from the first tick on.
     */
    @Test
    void aBoatSteeredIntoTheShoreReportsGround() throws Exception {
        try (var f = mount(FLOOR_Y - 2, SETTLED_Y, true, Vec3.ZERO)) {
            for (int tick = 0; tick < 6; tick++) {
                f.input(FORWARD);
                f.rideTick(new Vec3(0.5, SETTLED_Y, FLUSH_Z), true);
                assertEquals(0.0, groundViolations(f), "tick " + tick);
            }
            assertClean(f);
        }
    }

    @Test
    void aBoatSteeredIntoTheShoreCannotDenyGround() throws Exception {
        try (var f = mount(FLOOR_Y - 2, SETTLED_Y, true, Vec3.ZERO)) {
            for (int tick = 0; tick < 2; tick++) {
                f.input(FORWARD);
                f.rideTick(new Vec3(0.5, SETTLED_Y, FLUSH_Z), true);
            }
            assertEquals(0.0, groundViolations(f));
            f.input(FORWARD);
            f.rideTick(new Vec3(0.5, SETTLED_Y, FLUSH_Z), false);
            assertTrue(groundViolations(f) > 0);
        }
    }

    @Test
    void aBoatRisingWhileSteeredIntoTheShoreStaysOffTheGround() throws Exception {
        try (var f = mount(FLOOR_Y - 1, REST_Y, true, Vec3.ZERO)) {
            for (int tick = 0; tick < 4; tick++) {
                f.input(FORWARD);
                f.rideTick(new Vec3(0.5, REST_Y, FLUSH_Z), false);
                assertEquals(0.0, groundViolations(f), "tick " + tick);
            }
            f.input(FORWARD);
            f.rideTick(new Vec3(0.5, REST_Y, FLUSH_Z), true);
            assertTrue(groundViolations(f) > 0);
        }
    }

    @Test
    void aBoatInOpenWaterCannotClaimGround() throws Exception {
        try (var f = mount(FLOOR_Y - 2, SETTLED_Y, false, Vec3.ZERO)) {
            f.rideTick(new Vec3(0.5, SETTLED_Y, FLUSH_Z), true);
            assertTrue(groundViolations(f) > 0);
        }
    }

    /**
     * A boat paddled against a cow (AbstractBoat#canVehicleCollide admits pushable entities) moves 0 and Entity#move
     * zeroes deltaMovement.z each tick; once the cow is gone the boat starts from 0 + 0.04F.
     */
    @Test
    void aBoatPaddledAgainstACowThenFreed() throws Exception {
        paddleAgainstACow(2);
        paddleAgainstACow(14);
    }

    private static void paddleAgainstACow(int blocked) throws Exception {
        try (var f = mount(FLOOR_Y - 1, REST_Y, false, Vec3.ZERO)) {
            f.player.compensatedEntities.addEntity(COW, EntityTypeIds.COW, new Vec3(0.5, REST_Y, 2.45), 0, 0, 0);
            Vec3 seat = new Vec3(0.5, REST_Y, FLUSH_Z);
            f.input(NONE);
            f.rideTick(seat, false);
            for (int tick = 0; tick < blocked; tick++) {
                f.input(FORWARD);
                f.rideTick(seat, false);
            }
            f.player.compensatedEntities.removeEntity(COW);
            double z = FLUSH_Z;
            double vz = 0.0;
            for (int tick = 0; tick < 4; tick++) {
                vz = vz * 0.9F + (double) 0.04F;
                z += vz;
                f.input(FORWARD);
                PredictionResult result = f.rideTick(new Vec3(0.5, REST_Y, z), false);
                Vec3 offset = result.getTarget().subtract(result.getAcceptedClosestToTarget());
                assertTrue(offset.length() < 1.0E-5, blocked + " blocked, free tick " + tick);
            }
            assertEquals(0.0, groundViolations(f), blocked + " blocked");
            assertClean(f);
        }
    }

    private static double groundViolations(JavaTickFixture f) {
        return f.player.checkManager.getCheck(VehicleGround.class).violations;
    }

    private static void assertClean(JavaTickFixture f) {
        assertEquals(0.0, f.player.checkManager.getCheck(VehicleOffset.class).violations);
        assertEquals(0.0, f.player.checkManager.getCheck(OffsetHandler.class).violations);
    }

    private static JavaTickFixture mount(int waterY, double y, boolean shore) throws Exception {
        return mount(waterY, y, shore, new Vec3(0, 0, 0.01));
    }

    private static JavaTickFixture mount(int waterY, double y, boolean shore, Vec3 velocity) throws Exception {
        var f = new JavaTickFixture();
        f.player.checkManager.getCheck(VehicleGround.class).setEnabled(true);
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
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
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
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(velocity);
        return f;
    }
}
