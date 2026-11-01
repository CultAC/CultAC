package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Entity#move on a ridden root vehicle: setOnGroundWithMovement takes the move's verticalCollisionBelow, set only when
 * collision changes a downward move's y, and Entity#checkSupportingBlock stores the block under the moved box, or none
 * off the ground.
 */
class VehicleSupportingBlockTest {
    private static final int VEHICLE = 100;
    private static final BlockPos FLOOR = new BlockPos(0, FLOOR_Y - 1, 0);
    private static final MainSupportingBlockData ON_FLOOR = new MainSupportingBlockData(FLOOR, true);
    private static final MainSupportingBlockData AIRBORNE = new MainSupportingBlockData(null, false);
    /** LivingEntity#travelInAir on a 0.6-friction block. */
    private static final double GROUND_FRICTION = 0.6F * 0.91F;
    /** LivingEntity#travelInAir: a grounded horse's (0 - 0.08) * 0.98F. */
    private static final double GROUNDED_Y = -0.0784000015258789;

    private static final ServerboundPlayerInput NO_KEYS =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    /** A no-gravity horse at rest on stone requests no y movement, so its move leaves it off the ground. */
    @Test
    void aNoGravityHorseAtRestHasNoSupportingBlock() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntity horse = mount(f, EntityTypeIds.HORSE, new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO, true);
            horse.hasGravity = false;

            assertSupport(AIRBORNE, ride(f, new Vec3(0.5, FLOOR_Y, 0.5), false));
        }
    }

    /**
     * A horse rolling east off the stone at x 0, its velocity scaled by the ground friction after each grounded move.
     * The first move ends over the stone. The second move's y-first collision keeps it on the ground with no block under
     * its moved box, so checkSupportingBlock takes the stone under the box moved back by the horizontal movement. The
     * third move falls freely.
     */
    @Test
    void aHorseRollingOffALedgeKeepsThenLosesItsSupportingBlock() throws Exception {
        try (var f = new JavaTickFixture()) {
            airEastOfX0(f);
            mount(f, EntityTypeIds.HORSE, new Vec3(1.4, FLOOR_Y, 0.5), new Vec3(0.25, GROUNDED_Y, 0), true);

            double velocity = 0.25;
            double x = 1.4 + velocity;
            assertSupport(ON_FLOOR, ride(f, new Vec3(x, FLOOR_Y, 0.5), true));
            velocity *= GROUND_FRICTION;
            x += velocity;
            assertSupport(ON_FLOOR, ride(f, new Vec3(x, FLOOR_Y, 0.5), true));
            velocity *= GROUND_FRICTION;
            x += velocity;
            assertSupport(AIRBORNE, ride(f, new Vec3(x, FLOOR_Y + GROUNDED_Y, 0.5), false));
        }
    }

    /** A horse jumping into a ceiling: the collision cuts an upward move, which leaves it off the ground. */
    @Test
    void aHorseJumpingIntoACeilingHasNoSupportingBlock() throws Exception {
        try (var f = new JavaTickFixture()) {
            ceilingAtY66(f);
            PacketEntityHorse horse = (PacketEntityHorse)
                    mount(f, EntityTypeIds.HORSE, new Vec3(0.5, FLOOR_Y, 0.5), new Vec3(0, GROUNDED_Y, 0), true);
            horse.horseJump = 1.0;

            assertSupport(AIRBORNE, ride(f, new Vec3(0.5, 66.0 - (double) 1.6F, 0.5), false));
        }
    }

    /**
     * A boat falling onto stone: AbstractBoat#floatBoat adds -0.04 in the air, and the collision cuts the downward move
     * at the floor.
     */
    @Test
    void aBoatLandingOnStoneIsSupportedByIt() throws Exception {
        try (var f = new JavaTickFixture()) {
            mount(f, EntityTypeIds.OAK_BOAT, new Vec3(0.5, FLOOR_Y + 0.1, 0.5), new Vec3(0, -0.16, 0), false);

            assertSupport(ON_FLOOR, ride(f, new Vec3(0.5, FLOOR_Y, 0.5), true));
        }
    }

    /** Air in place of the fixture's stone floor east of x 0. */
    private static void airEastOfX0(JavaTickFixture f) {
        for (int x = 1; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y - 1, z), BlockIds.AIR.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    /** Stone at y 66 over x -3..3, z -3..3. */
    private static void ceilingAtY66(JavaTickFixture f) {
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, 66, z), BlockIds.STONE.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    /** The vehicle at {@code start} carrying {@code velocity}, ridden with no keys held. */
    private static PacketEntity mount(JavaTickFixture f, int type, Vec3 start, Vec3 velocity, boolean onGround) {
        f.player.compensatedEntities.addEntity(VEHICLE, type, start, 0, 0, 0);
        PacketEntity vehicle = f.player.compensatedEntities.getEntity(VEHICLE);
        if (vehicle instanceof PacketEntityHorse horse) {
            horse.hasSaddle = true;
            horse.isTame = true;
        }
        vehicle.onGround = onGround;
        f.setPassengers(VEHICLE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.input(NO_KEYS);
        f.tickEnd();
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(velocity);
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.fromBoolean(onGround));
        return vehicle;
    }

    /** One client tick: the rider's Rot, the vehicle's move and the tick end. */
    private static PredictionResult ride(JavaTickFixture f, Vec3 to, boolean onGround) {
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        var rot = new ServerboundMovePlayer(0, 0, 0, 0F, 0F, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        var move = new ServerboundMoveVehicle(new Vec3d(to.x, to.y, to.z), 0F, 0F, onGround, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, move), f.player, move);
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertTrue(result != null && result != before, "the vehicle move was not predicted");
        return result;
    }

    private static void assertSupport(MainSupportingBlockData expected, PredictionResult result) {
        assertTrue(exact(result), describe(result));
        assertEquals(expected, result.getSimulationContext().getWorldData().getMainSupportingBlockPos());
    }
}
