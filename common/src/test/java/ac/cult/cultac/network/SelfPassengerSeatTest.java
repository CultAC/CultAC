package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static ac.cult.cultac.network.JavaTickFixture.observeOnly;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * An entity rides the local player while the player rides nothing. ClientLevel#tickNonPassenger ticks the player, then
 * ClientLevel#tickPassenger runs the rider's Entity#rideTick, whose Entity#positionRider seats it from the one
 * position the player holds after that tick.
 */
class SelfPassengerSeatTest {
    private static final int STAND = 100;
    // Player#STANDING_DIMENSIONS is 1.8F tall and has no passenger attachment of its own, so the seat is its top.
    private static final double SEAT_HEIGHT = 1.8F;
    // Player#POSES gives CROUCHING a 1.5F tall box.
    private static final double CROUCHING_SEAT_HEIGHT = 1.5F;
    private static final ServerboundPlayerInput NO_KEYS =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput SHIFT =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);

    @Test
    void aRiderOfTheOnFootPlayerIsSeatedFromThePlayersPositionAfterEachTick() throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observeOnly()) {
            var entities = f.player.compensatedEntities;
            Vec3 position = new Vec3(0.5, FLOOR_Y + 10, 0.5);
            f.place(position, Vec3.ZERO);
            entities.addEntity(STAND, EntityTypeIds.ARMOR_STAND, new Vec3(4, FLOOR_Y, 4), 0, 0, 0);
            f.setPassengers(f.player.entityID, List.of(STAND));
            f.pongThrough(f.player.lastTransactionSent.get());
            PacketEntity stand = entities.getEntity(STAND);
            assertSame(entities.getSelf(), stand.getRiding());

            // LivingEntity#travel moves by the current delta, then applies gravity and drag.
            double motion = 0;
            for (int tick = 0; tick < 3; tick++) {
                position = new Vec3(position.x, position.y + motion, position.z);
                motion = (motion - 0.08) * 0.98F;
                PredictionResult result = f.tick(position, false);
                assertTrue(exact(result), "tick " + tick + ": " + describe(result));
                assertEquals(1, stand.possibleMovementCollisionBoxCandidateCount(), "seats after tick " + tick);
                SimpleCollisionBox seat = stand.getPossibleMovementCollisionBoxes();
                assertEquals(position.x, (seat.minX + seat.maxX) / 2, 1.0E-9, "seat x after tick " + tick);
                assertEquals(position.y + SEAT_HEIGHT, seat.minY, 1.0E-9, "seat y after tick " + tick);
                assertEquals(position.z, (seat.minZ + seat.maxZ) / 2, 1.0E-9, "seat z after tick " + tick);
            }
        }
    }

    /**
     * Player#tick ends with Player#updatePlayerPose before ClientLevel#tickPassenger seats the riders, also in a tick
     * whose LocalPlayer#sendPosition sends nothing.
     */
    @Test
    void aRiderIsSeatedFromThePoseOfATickThatSentNoMovement() throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observeOnly()) {
            PacketEntity stand = standingRiderOnTheFloor(f);

            f.input(SHIFT);
            f.tickEnd();
            assertSame(Pose.CROUCHING, f.player.pose);
            assertEquals(FLOOR_Y + CROUCHING_SEAT_HEIGHT, stand.getPossibleMovementCollisionBoxes().minY, 1.0E-9);

            f.input(NO_KEYS);
            f.tickEnd();
            assertSame(Pose.STANDING, f.player.pose);
            assertEquals(FLOOR_Y + SEAT_HEIGHT, stand.getPossibleMovementCollisionBoxes().minY, 1.0E-9);
        }
    }

    /** Entity#stopRiding moves nothing: the rider stays on the seat its last Entity#rideTick gave it. */
    @Test
    void aRiderUnseatedAfterACrouchingTickThatSentNoMovementStaysOnTheCrouchingSeat() throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observeOnly()) {
            PacketEntity stand = standingRiderOnTheFloor(f);

            f.input(SHIFT);
            f.tickEnd();
            f.setPassengers(f.player.entityID, List.of());
            f.pongThrough(f.player.lastTransactionSent.get());
            f.tickEnd();
            assertEquals(1, stand.possibleMovementCollisionBoxCandidateCount());
            assertEquals(FLOOR_Y + CROUCHING_SEAT_HEIGHT, stand.getPossibleMovementCollisionBoxes().minY, 1.0E-9);
        }
    }

    private static PacketEntity standingRiderOnTheFloor(JavaTickFixture f) {
        var entities = f.player.compensatedEntities;
        f.place(new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO);
        entities.addEntity(STAND, EntityTypeIds.ARMOR_STAND, new Vec3(4, FLOOR_Y, 4), 0, 0, 0);
        f.setPassengers(f.player.entityID, List.of(STAND));
        f.pongThrough(f.player.lastTransactionSent.get());
        PacketEntity stand = entities.getEntity(STAND);
        f.tickEnd();
        assertSame(Pose.STANDING, f.player.pose);
        assertEquals(FLOOR_Y + SEAT_HEIGHT, stand.getPossibleMovementCollisionBoxes().minY, 1.0E-9);
        return stand;
    }
}
