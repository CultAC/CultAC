package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static ac.cult.cultac.network.JavaTickFixture.setField;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * A player beside boats whose position updates the client has not answered. The client holds each boat at one of
 * its possible positions, and Entity#collide collides with each boat's bounding box there
 * (EntityGetter#getEntityCollisions).
 */
class JavaBoatPositionsTest {
    private static final int BOAT = 100;
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final float SPEED = 0.1F;
    private static final double GROUNDED_Y = -0.0784000015258789;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    // AbstractBoat is 1.375 wide and 0.5625 tall.
    private static final double BOAT_HALF_WIDTH = 1.375 / 2.0;
    private static final double BOAT_TOP = 0.5625;
    // The player's box is 1.8 tall.
    private static final double PLAYER_HEIGHT = 1.8;

    @Test
    void aTickAmongManyBoatsAwaitingPositionsCompletes() {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            try (var f = new JavaTickFixture()) {
                Vec3[] boats = new Vec3[8];
                for (int i = 0; i < boats.length; i++) {
                    boats[i] = new Vec3(START.x + 0.3 + BOAT_HALF_WIDTH + 0.001 * i, FLOOR_Y, START.z + 0.07 * i);
                    f.player.compensatedEntities.addEntity(BOAT + i, EntityTypeIds.OAK_BOAT, boats[i], 0, 0, 0);
                }
                for (int tick = 0; tick < 3; tick++) {
                    for (int i = 0; i < boats.length; i++) {
                        PacketEntity boat = f.player.compensatedEntities.getEntity(BOAT + i);
                        double dy = 0.01 * (tick + 1) * (i % 2 == 0 ? 1 : -1);
                        boat.onFirstTransaction(
                                false, true, boats[i].x, boats[i].y - dy, boats[i].z + 0.003 * (tick + 1), f.player);
                    }
                    start(f, NONE, 0.0F);
                    f.tick(START.add(0.0, 0.3, 0.0), true);
                }
            }
        });
    }

    @Test
    void hoveringBesideABoatAtItsTopIsNotOnGround() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 boat = new Vec3(START.x + 0.3 + 0.0125 + BOAT_HALF_WIDTH, FLOOR_Y, START.z);
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, boat, 0, 0, 0);
            Vec3 hover = START.add(0.0, BOAT_TOP, 0.0);
            for (int tick = 0; tick < 3; tick++) {
                f.place(hover, Vec3.ZERO);
                f.player.onGround = f.player.lastOnGround = true;
                f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
                f.player.xRot = f.player.lastTickXRot = 0.0F;
                f.input(NONE);
                assertRejected(f.tick(hover, true), "GroundSpoof");
            }
        }
    }

    @Test
    void driftingBelowABoatAwaitingAPositionWithoutInputIsRejected() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 boat = new Vec3(START.x, FLOOR_Y + PLAYER_HEIGHT + 0.05, START.z);
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, boat, 0, 0, 0);
            PacketEntity entity = f.player.compensatedEntities.getEntity(BOAT);
            entity.onFirstTransaction(false, true, boat.x, boat.y - 0.01, boat.z + 0.003, f.player);
            start(f, NONE, 0.0F);
            PredictionResult still = f.tick(START, true);
            assertTrue(exact(still), describe(still));
            entity.onFirstTransaction(false, true, boat.x, boat.y - 0.02, boat.z + 0.006, f.player);
            PredictionResult drift = f.tick(START.add(0.0, 0.0, 0.05), true);
            assertRejected(drift, "InputKeys");
        }
    }

    @Test
    void stoppingOnBothAxesAgainstOneBoatAtEitherOfTwoPositionsIsRejected() throws Exception {
        try (var f = new JavaTickFixture()) {
            // East of the player's box, then south of it.
            Vec3 east = new Vec3(START.x + 0.3 + BOAT_HALF_WIDTH, FLOOR_Y, START.z);
            Vec3 south = new Vec3(START.x, FLOOR_Y, START.z + 0.3 + BOAT_HALF_WIDTH);
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, east, 0, 0, 0);
            f.player
                    .compensatedEntities
                    .getEntity(BOAT)
                    .onFirstTransaction(false, true, south.x, south.y, south.z, f.player);
            start(f, NONE, 0.0F);
            f.place(START, new Vec3(0.1, GROUNDED_Y, 0.1));
            assertRejected(f.tick(START, true), "Speed");
        }
    }

    private static void start(JavaTickFixture f, ServerboundPlayerInput keys, float yaw) throws Exception {
        f.place(START, new Vec3(0.0, GROUNDED_Y, 0.0));
        f.player.xRot = f.player.lastTickXRot = yaw;
        setField(SimulationProcessor.class, f.player.checkManager.getSimulationProcessor(), "lastSneaking", false);
        f.input(keys);
    }
}
