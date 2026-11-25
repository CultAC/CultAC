package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.vehicle.VehicleGround;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundInitializeBorder;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetBorderLerpSize;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.enums.BoatEntityStatus;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Entity#collide gathers the world border's shape whenever WorldBorder#isInsideCloseToBorder holds: the boat is
 * closer to the border than twice the larger horizontal size of its box expanded towards its movement. Shapes#collide
 * then returns 0 for a vertical distance under 1.0E-7, so Entity#move records a vertical collision, zeroes the vertical
 * deltaMovement and sends onGround true exactly when AbstractBoat#floatBoat's distance was negative.
 */
class BoatAtWorldBorderTest {
    private static final int BOAT = 100;
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    /** A border of 1600 around 0, 0: its south edge is at z 800. */
    private static final double SIZE = 1600.0;

    private static final double X = -82.58901773473536;
    /** The boat's south face touches the border. */
    private static final double Z = 799.3125;
    /** Water at y 62: floatBoat from rest moves by -4.683753385137379E-16. */
    private static final double FALLING_Y = 62.523264312744146;
    /** Water at y 63: floatBoat from rest moves by 1.1449174941446927E-16. */
    private static final double RISING_Y = 63.52326431274414;

    @Test
    void aBoatIdleAgainstTheBorderReportsGround() throws Exception {
        idleAgainstTheBorder(ProtocolVersion.V26_3, ClientVersion.V_26_3);
        idleAgainstTheBorder(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11);
    }

    private static void idleAgainstTheBorder(ProtocolVersion protocol, ClientVersion client) throws Exception {
        try (var f = new JavaTickFixture(protocol, client)) {
            initializeBorder(f, SIZE);
            Vec3 seat = mount(f, 62, new Vec3(X, FALLING_Y, Z));
            for (int tick = 0; tick < 6; tick++) {
                assertVehicleExact(f.rideTick(seat, true), tick);
                assertEquals(0.0, groundViolations(f), client + " tick " + tick);
            }
        }
    }

    @Test
    void aBoatIdleAwayFromTheBorderCannotClaimGround() throws Exception {
        try (var f = new JavaTickFixture()) {
            initializeBorder(f, SIZE);
            Vec3 seat = mount(f, 62, new Vec3(X, FALLING_Y, Z - 10.0));
            f.rideTick(seat, true);
            assertTrue(groundViolations(f) > 0);
        }
    }

    @Test
    void aBoatRisingAgainstTheBorderStaysOffTheGround() throws Exception {
        try (var f = new JavaTickFixture()) {
            initializeBorder(f, SIZE);
            Vec3 seat = mount(f, 63, new Vec3(X, RISING_Y, Z));
            for (int tick = 0; tick < 4; tick++) assertVehicleExact(f.rideTick(seat, false), tick);
            assertEquals(0.0, groundViolations(f));
            f.rideTick(seat, true);
            assertTrue(groundViolations(f) > 0);
        }
    }

    @Test
    void aBoatFallingAgainstTheBorderCannotDenyGround() throws Exception {
        try (var f = new JavaTickFixture()) {
            initializeBorder(f, SIZE);
            Vec3 seat = mount(f, 62, new Vec3(X, FALLING_Y, Z));
            f.rideTick(seat, true);
            f.rideTick(seat, true);
            assertEquals(0.0, groundViolations(f));
            f.rideTick(seat, false);
            assertTrue(groundViolations(f) > 0);
        }
    }

    /** A boat at rest expands towards no horizontal movement, so bbMax is its 1.375 width. */
    @Test
    void theBorderIsGatheredWithinTwiceTheBoatsWidth() throws Exception {
        try (var f = new JavaTickFixture()) {
            initializeBorder(f, SIZE);
            Vec3 seat = mount(f, 62, new Vec3(X, FALLING_Y, 800.0 - 2.75 + 1.0E-6));
            f.rideTick(seat, true);
            f.rideTick(seat, true);
            assertEquals(0.0, groundViolations(f));
            f.rideTick(seat, false);
            assertTrue(groundViolations(f) > 0);
        }
        try (var f = new JavaTickFixture()) {
            initializeBorder(f, SIZE);
            Vec3 seat = mount(f, 62, new Vec3(X, FALLING_Y, 800.0 - 2.75));
            for (int tick = 0; tick < 3; tick++) f.rideTick(seat, false);
            assertEquals(0.0, groundViolations(f));
            f.rideTick(seat, true);
            assertTrue(groundViolations(f) > 0);
        }
    }

    /**
     * WorldBorder#tick runs after ClientLevel#tickEntities, and MovingBorderExtent#getMinX(0.0F) reads the size before
     * the last update: shrinking by 0.1 a tick from 1600, the border reaches 2.75 from the boat after 10 updates and
     * the boat first gathers it on the tick after the 11th.
     */
    @Test
    void aShrinkingBorderIsGatheredOnTheTickItComesWithinReach() throws Exception {
        shrinkingBorder(ProtocolVersion.V26_3, ClientVersion.V_26_3);
        shrinkingBorder(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11);
        try (var f = new JavaTickFixture()) {
            Vec3 seat = shrinkTowards(f, true);
            for (int tick = 0; tick < 3; tick++) f.rideTick(seat, false);
            assertEquals(0.0, groundViolations(f));
            f.rideTick(seat, true);
            assertTrue(groundViolations(f) > 0);
        }
    }

    private static void shrinkingBorder(ProtocolVersion protocol, ClientVersion client) throws Exception {
        try (var f = new JavaTickFixture(protocol, client)) {
            Vec3 seat = shrinkTowards(f, true);
            for (int tick = 0; tick < 20; tick++) {
                assertVehicleExact(f.rideTick(seat, tick >= 12), tick);
                assertEquals(0.0, groundViolations(f), client + " tick " + tick);
            }
            f.rideTick(seat, false);
            assertTrue(groundViolations(f) > 0);
        }
    }

    /** The client starts a lerp on the tick it handles the packet, which can follow the tick the ping settles on. */
    @Test
    void aShrinkingBorderStartedOnALaterClientTick() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 seat = shrinkTowards(f, false);
            for (int tick = 0; tick < 20; tick++) {
                assertVehicleExact(f.rideTick(seat, tick % 2 == 0), tick);
                assertEquals(0.0, groundViolations(f), "tick " + tick);
            }
        }
    }

    /** A boat 3.25 from the south edge of a border that starts shrinking by 0.1 a tick, settled in the same tick. */
    private static Vec3 shrinkTowards(JavaTickFixture f, boolean sameTick) {
        initializeBorder(f, SIZE);
        Vec3 seat = mount(f, 62, new Vec3(X, FALLING_Y, 800.0 - 3.25));
        for (int tick = 0; tick < 2; tick++) assertVehicleExact(f.rideTick(seat, false), tick);
        var packet = new ClientboundSetBorderLerpSize(SIZE, SIZE - 10.0, 100L);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.SET_BORDER_LERP_SIZE, packet, false);
        f.player.checkManager.getWorldBorder().onSetBorderLerpSize(event, f.player, packet);
        f.pongThrough(f.player.lastTransactionSent.get());
        if (!sameTick) f.rideTick(seat, false);
        f.player.sendTransaction();
        f.pongThrough(f.player.lastTransactionSent.get());
        return seat;
    }

    private static void initializeBorder(JavaTickFixture f, double size) {
        var packet = new ClientboundInitializeBorder(0.0, 0.0, size, size, 0L, 29_999_984);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.INITIALIZE_BORDER, packet, false);
        f.player.checkManager.getWorldBorder().onInitializeBorder(event, f.player, packet);
        f.pongThrough(f.player.lastTransactionSent.get());
        f.player.sendTransaction();
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static double groundViolations(JavaTickFixture f) {
        return f.player.checkManager.getCheck(VehicleGround.class).violations;
    }

    /** An oak boat in water at {@code waterY} around {@code seat}, ridden by the player and at rest. */
    private static Vec3 mount(JavaTickFixture f, int waterY, Vec3 seat) {
        f.player.checkManager.getCheck(VehicleGround.class).setEnabled(true);
        int blockX = (int) Math.floor(seat.x);
        int blockZ = (int) Math.floor(seat.z);
        for (int chunkX = (blockX >> 4) - 1; chunkX <= (blockX >> 4) + 1; chunkX++) {
            for (int chunkZ = (blockZ >> 4) - 1; chunkZ <= (blockZ >> 4) + 1; chunkZ++) {
                f.player.compensatedWorld.ensureValidationChunkLoaded(chunkX, chunkZ);
            }
        }
        for (int x = blockX - 4; x <= blockX + 4; x++) {
            for (int z = blockZ - 4; z <= blockZ + 4; z++) {
                for (int above = waterY + 1; above <= waterY + 3; above++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, above, z), BlockIds.AIR.defaultState());
                }
                f.player.compensatedWorld.updateBlock(new BlockPos(x, waterY, z), BlockIds.WATER.defaultState());
                f.player.compensatedWorld.updateBlock(new BlockPos(x, waterY - 1, z), BlockIds.STONE.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.place(seat, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, seat, 0, 0, 0);
        f.player.compensatedEntities.getEntity(BOAT).boatStatus = BoatEntityStatus.IN_WATER;
        f.setPassengers(BOAT, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.player.xRot = 0;
        f.player.yRot = 0;
        f.input(NONE);
        f.tickEnd();
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(Vec3.ZERO);
        return seat;
    }
}
