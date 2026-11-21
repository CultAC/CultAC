package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.NoFallPseudo;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMoveCollision;
import ac.cult.cultac.checks.impl.vehicle.VehicleGround;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPaddleBoat;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.JavaCollisionState;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JavaBoatTinyAxisCollisionTest {
    private static final int BOAT = 100;
    private static final Vec3 START = new Vec3(0.5, 70.0, 0.5);
    private static final double GAP = 0.0625;
    private static final double HORIZONTAL_CARRY = 0.125;
    private static final double BOAT_GRAVITY = 0.04;

    /**
     * The rider reports airborne. The root's own Entity#move can set its ground bit when Shapes.collide zeroes
     * tiny negative Y before visiting a boat beside it. The reported height rounds to the same double either way.
     */
    @ParameterizedTest
    @CsvSource({
        "true,  true,  true",
        "false, true,  false",
        "false, true,  true",
        "true,  false, true"
    })
    void checksTheRootsGroundFromItsOwnCollision(boolean lateralBoat, boolean tinyDownward, boolean reportsGround)
            throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            f.place(START, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, START, 0, 0, 0);
            var boat = f.player.compensatedEntities.getEntity(BOAT);
            var vehicleGround = f.player.checkManager.getCheck(VehicleGround.class);
            vehicleGround.setEnabled(true);
            if (lateralBoat) {
                Vec3 other = START.add(0.0, 0.0, 1.375 + GAP);
                f.player.compensatedEntities.addEntity(BOAT + 1, EntityTypeIds.OAK_BOAT, other, 0, 0, 0);
            }
            f.setPassengers(BOAT, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.tickEnd();

            var runner = f.player.checkManager.getSimulationProcessor();
            // Seed the stored velocity before AbstractBoat#floatBoat adds gravity.
            double carryY = tinyDownward ? Math.nextDown(BOAT_GRAVITY) : BOAT_GRAVITY;
            runner.seedStartingVelocity(new Vec3(0.0, carryY, HORIZONTAL_CARRY));
            runner.setLastOnGround(DesyncStatus.FALSE);
            var before = runner.getLastPrediction();
            var paddle = new ServerboundPaddleBoat(false, false);
            CheckManagerListener.receiveGeneric(f.event(ServerboundPackets.PADDLE_BOAT, paddle), f.player);
            var rot = new ServerboundMovePlayer(0, 0, 0, 0, 0, false, false, false, true);
            f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
            double dz = lateralBoat ? GAP : HORIZONTAL_CARRY * 0.9F;
            f.moveVehicle(START.add(0.0, 0.0, dz), 0, 0, reportsGround);
            f.tickEnd();

            var result = runner.getLastPrediction();
            assertTrue(result != null && result != before, "the vehicle move was not predicted");
            assertFalse(result.isExempt(), describe(result));
            var collision = assertInstanceOf(JavaMoveCollision.class, result.getCollideAxisData());
            boolean vanillaGround = lateralBoat && tinyDownward;
            boolean shouldFlag = reportsGround != vanillaGround;
            assertAll(
                    () -> assertEquals(carryY - BOAT_GRAVITY, collision.attempted().y, collision::toString),
                    () -> assertEquals(vanillaGround ? 0.0 : collision.attempted().y, collision.collided().y),
                    () -> assertEquals(lateralBoat, collision.collidedZ(), collision::toString),
                    () -> assertEquals(vanillaGround, collision.verticalCollisionBelow(), collision::toString),
                    () -> assertEquals(shouldFlag, result.hasFlag(NoFallPseudo.class), describe(result)),
                    () -> assertEquals(shouldFlag, vehicleGround.violations > 0.0, describe(result)),
                    () -> assertEquals(vanillaGround, boat.onGround, "ground carried by the root"),
                    () -> assertEquals(DesyncStatus.fromBoolean(vanillaGround), runner.getLastOnGround()));
        }
    }

    @Test
    void checksTheStrictThresholdBeforeEachShape() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            var actor = SimpleCollisionBox.between(0, 70, 0, 1, 71, 1);
            var lateral = SimpleCollisionBox.between(0, 70, 1 + GAP, 1, 71, 2);
            var below = SimpleCollisionBox.between(0, 69, 0, 1, 70 - 5.0E-8, 1);
            assertAll(
                    () -> assertEquals(-5.0E-8, collideY(f, actor, -5.0E-8, List.of()), "empty sweep"),
                    () -> assertEquals(0.0, collideY(f, actor, -5.0E-8, List.of(lateral)), "before the first shape"),
                    () -> assertEquals(-1.0E-7, collideY(f, actor, -1.0E-7, List.of(lateral)), "exact threshold"),
                    () -> assertEquals(
                            below.maxY - actor.minY, collideY(f, actor, -0.25, List.of(below)), "last shape's clip"),
                    () -> assertEquals(
                            0.0, collideY(f, actor, -0.25, List.of(below, lateral)), "before the second shape"));
        }
    }

    private static double collideY(
            JavaTickFixture f, SimpleCollisionBox actor, double dy, List<SimpleCollisionBox> colliders) {
        return Collisions.collideEntity(
                f.player, actor, new Vec3(0, dy, HORIZONTAL_CARRY), false, 0,
                JavaCollisionState.current(f.player), colliders).y;
    }
}
