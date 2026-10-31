package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static ac.cult.cultac.network.JavaTickFixture.observeOnly;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The local player drives a boat with a pig in its back seat, then the server takes the player off.
 * ClientPacketListener#handleSetEntityPassengersPacket only ejects and relinks the passengers: Entity#startRiding does
 * not call positionRider. The pig keeps the back seat it had as the second of two passengers until its next
 * Entity#rideTick, and its LivingEntity#pushEntities pushes from there before positionRider centres it.
 */
class JavaBoatDismountRiderSeatTest {
    private static final int BOAT = 100;
    private static final int PIG = 101;
    private static final float YAW = 30.0F;
    private static final float PITCH = 0.0F;
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final Vec3 REST = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 DISMOUNT = new Vec3(-0.01, FLOOR_Y, 0.5);

    @Test
    void aRetainedRemoteRiderKeepsItsSeatWhenThePlayerJoinsItsTree() throws Exception {
        try (var f = new JavaTickFixture()) {
            var entities = f.player.compensatedEntities;
            entities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, REST, YAW, 0, 0);
            entities.addEntity(PIG, EntityTypeIds.PIG, new Vec3(4, FLOOR_Y, 4), 0, 0, 0);
            entities.vehicles.applyVehiclePassengers(BOAT, new int[] {PIG});
            entities.tickClientEntities(entity -> {});
            f.player.packetStateData.acceptedClientTick++;
            PacketEntity pig = entities.getEntity(PIG);

            entities.vehicles.applyVehiclePassengers(BOAT, new int[] {f.player.entityID, PIG});

            assertCentre(REST, pig.getPossibleMovementCollisionBoxes(), "retained seat when the player mounts");
            assertEquals(List.of(entities.getSelf(), pig), entities.getEntity(BOAT).passengers);
            entities.tickClientEntities(entity -> {});
            assertCentre(seat(-0.4F), pig.getPossibleMovementCollisionBoxes(), "seat after rideTick");
        }
    }

    @Test
    void relinkingARemoteRiderKeepsItsCommittedSeatAndPacketInterpolation() throws Exception {
        try (var f = new JavaTickFixture()) {
            var entities = f.player.compensatedEntities;
            entities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, REST, YAW, 0, 0);
            entities.addEntity(PIG, EntityTypeIds.PIG, new Vec3(4, FLOOR_Y, 4), 0, 0, 0);
            entities.vehicles.applyVehiclePassengers(BOAT, new int[] {PIG});
            entities.tickClientEntities(entity -> {});
            f.player.packetStateData.acceptedClientTick++;
            PacketEntity pig = entities.getEntity(PIG);
            SimpleCollisionBox seat = pig.getPossibleMovementCollisionBoxes();
            var interpolation = pig.newPacketLocation;

            entities.vehicles.applyVehiclePassengers(BOAT, new int[] {PIG});

            SimpleCollisionBox relinked = pig.getPossibleMovementCollisionBoxes();
            assertEquals(seat.minX, relinked.minX);
            assertEquals(seat.minY, relinked.minY);
            assertEquals(seat.minZ, relinked.minZ);
            assertSame(interpolation, pig.newPacketLocation);
        }
    }

    @Test
    void theRiderLeftAboardKeepsItsSeatUntilItsNextRideTick() throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observeOnly()) {
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            f.place(REST, Vec3.ZERO);
            f.player.xRot = YAW;
            f.player.yRot = PITCH;
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, REST, YAW, 0, 0);
            f.player.compensatedEntities.addEntity(PIG, EntityTypeIds.PIG, REST, 0, 0, 0);
            PacketEntity pig = f.player.compensatedEntities.getEntity(PIG);
            f.setPassengers(BOAT, List.of(f.player.entityID, PIG));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.input(NONE);
            for (int tick = 0; tick < 3; tick++) assertVehicleExact(rideTick(f), tick);
            // AbstractBoat#getPassengerAttachmentPoint: the second of two passengers sits at -0.6, an animal 0.2
            // further forward, rotated by the boat's yaw.
            Vec3 backSeat = seat(-0.4F);
            assertCentre(backSeat, pig.getPossibleMovementCollisionBoxes(), "seat while both ride");

            f.setPassengers(BOAT, List.of(PIG));
            f.pongThrough(f.player.lastTransactionSent.get());

            assertEquals(BOAT, pig.getRiding().getEntityId());
            List<SimpleCollisionBox> pushesFrom = f.player.compensatedEntities.untrackedRiderSeatsBeforeTick(pig);
            assertEquals(1, pushesFrom.size(), "pig push positions " + pushesFrom);
            assertCentre(backSeat, pushesFrom.getFirst(), "seat the pig pushes from after the dismount");

            // The server's dismount teleport, beside the boat and inside its push box.
            f.playerPosition(DISMOUNT, Vec3.ZERO, Set.of(Relative.Y_ROT, Relative.X_ROT));
            // The teleport zeroed the velocity, so Entity#move hits nothing below and the client reports no ground.
            assertExact(f.tick(DISMOUNT, false));
            // The pig's next Entity#rideTick reseats it as the only passenger, in the middle of the boat.
            assertCentre(
                    seat(0.0F),
                    f.player.compensatedEntities.untrackedRiderSeatsBeforeTick(pig).getFirst(),
                    "seat after the pig's ride tick");

            // Both tick after the player: the boat pushes from its centre, then the pig from its back seat.
            Vec3 pushed = DISMOUNT.add(push(REST, DISMOUNT)).add(push(backSeat, DISMOUNT));
            assertExact(f.tick(pushed, true));
        }
    }

    /** Entity#push's impulse on an entity at {@code pushed} from a pusher at {@code pusher}, with its float constants. */
    private static Vec3 push(Vec3 pusher, Vec3 pushed) {
        double x = pusher.x - pushed.x, z = pusher.z - pushed.z;
        double root = Math.sqrt(Math.max(Math.abs(x), Math.abs(z)));
        double scale = Math.min(1.0D, 1.0D / root);
        return new Vec3(-x / root * scale * 0.05F, 0, -z / root * scale * 0.05F);
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }

    /** The point Entity#positionRider gives a passenger {@code offset} along the boat from its centre. */
    private static Vec3 seat(float offset) {
        double yaw = Math.toRadians(YAW);
        return new Vec3(REST.x - offset * Math.sin(yaw), REST.y, REST.z + offset * Math.cos(yaw));
    }

    /** Vec3#yRot rotates through Mth's sine table, within 1e-4 of the exact rotation and far from any other seat. */
    private static void assertCentre(Vec3 expected, SimpleCollisionBox box, String message) {
        double x = (box.minX + box.maxX) / 2, z = (box.minZ + box.maxZ) / 2;
        assertEquals(expected.x, x, 1.0E-4, message + " x: " + box);
        assertEquals(expected.z, z, 1.0E-4, message + " z: " + box);
    }

    /** Player#tick clears the passenger's ground bit while the boat remains grounded. */
    private static PredictionResult rideTick(JavaTickFixture f) {
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        var rot = new ServerboundMovePlayer(0, 0, 0, YAW, PITCH, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        f.moveVehicle(REST, YAW, PITCH, true);
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertTrue(result != null && result != before, "the vehicle move was not predicted");
        return result;
    }
}
