package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Entity#collide gathers entity colliders through EntityGetter#getEntityCollisions, which never returns the moving
 * entity, and AbstractBoat#canVehicleCollide rejects every entity Entity#isPassengerOfSameVehicle. A ridden boat in
 * open air therefore has nothing to land on: AbstractBoat#floatBoat's 0.04 gravity must show in every move.
 */
class BoatOwnCollisionTest {
    private static final int BOAT = 100;
    private static final Vec3 SEAT = new Vec3(0.5, FLOOR_Y + 10, 0.5);
    private static final int TICKS = 6;

    @Test
    void aFallingBoatInTheAirIsExact() throws Exception {
        try (var f = mount()) {
            Vec3 position = SEAT;
            Vec3 velocity = Vec3.ZERO;
            for (int tick = 0; tick < TICKS; tick++) {
                // AbstractBoat#floatBoat in the air: 0.9 friction and 0.04 gravity, then no input.
                velocity = new Vec3(velocity.x * 0.9F, velocity.y - 0.04F, velocity.z * 0.9F);
                position = position.add(velocity);
                PredictionResult result = f.rideTick(position, false);
                assertTrue(JavaTickFixture.exact(result), "tick " + tick + ": " + JavaTickFixture.describe(result));
            }
        }
    }

    @Test
    void aBoatHoveringWithoutGravityIsRejected() throws Exception {
        try (var f = mount()) {
            // A client skipping AbstractBoat#floatBoat keeps the boat at its height.
            JavaTickFixture.assertRejected(f.rideTick(SEAT, false), "VehicleOffset");
        }
    }

    private static JavaTickFixture mount() throws Exception {
        var f = new JavaTickFixture();
        f.place(SEAT, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, SEAT, 0, 0, 0);
        f.setPassengers(BOAT, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.player.xRot = 0;
        f.player.yRot = 0;
        return f;
    }
}
