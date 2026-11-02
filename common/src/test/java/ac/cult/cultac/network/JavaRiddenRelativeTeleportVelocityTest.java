package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityMotion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundTeleportEntity;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ClientPacketListener#handleTeleportEntity starts from PositionMoveRotation#of(entity), whose Entity#getKnownMovement a
 * vehicle the local player controls takes from LocalPlayer#getDeltaMovement: what the passenger's last Entity#rideTick
 * left, or a set_entity_motion handled since. The vehicle's own travel never enters it.
 */
class JavaRiddenRelativeTeleportVelocityTest {
    private static final int BOAT = 100;
    private static final Vec3 BOAT_AT = new Vec3(0.5, FLOOR_Y, 0.1);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final Set<Relative> DELTA = EnumSet.of(Relative.DELTA_X, Relative.DELTA_Y, Relative.DELTA_Z);
    private static final Vec3 CHANGE = new Vec3(0.1, 0.05, -0.08);
    /** Entity#rideTick zeroes the passenger, then LivingEntity#travelInAir applies gravity and the 0.98 drag. */
    private static final Vec3 RIDE_TICK = new Vec3(0, -0.08 * 0.98F, 0);
    /** AbstractBoat#floatBoat's ON_LAND friction, before the move: getGroundFriction's float mean of six stone blocks. */
    private static final double LAND_FRICTION = landFriction();

    @ParameterizedTest(name = "{0}: the boat adds what the passenger's ride tick left")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aVelocityRelativeTeleportAddsThePassengersRideTickMotion(String release, ClientVersion version)
            throws Exception {
        try (var f = mounted(version)) {
            // The next ride tick zeroes this motion before the teleport reads the passenger.
            motion(f, new Vec3(0.0, 0.25, 0.0));
            f.pongThrough(f.player.lastTransactionSent.get());
            assertVehicleExact(f.rideTick(BOAT_AT, true), 0);

            teleport(f, CHANGE, DELTA);
            Vec3 velocity = RIDE_TICK.add(CHANGE);
            assertStartsWith(f, velocity);

            // The client reports the interpolation target; the boat itself travels by the teleport's velocity.
            var result = f.rideTick(BOAT_AT, true);
            assertTrue(exact(result), describe(result));
            Vec3 travel = result.getAcceptedClosestToTarget();
            assertEquals(velocity.x * LAND_FRICTION, travel.x, 1e-12);
            assertEquals(0, travel.y, 1e-12, "the floor stops the fall");
            assertEquals(velocity.z * LAND_FRICTION, travel.z, 1e-12);
        }
    }

    @ParameterizedTest(name = "{0}: a motion handled before the teleport is what the boat adds")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aMotionTheClientHandledFirstIsWhatTheBoatAdds(String release, ClientVersion version) throws Exception {
        try (var f = mounted(version)) {
            // Both reach the client before any further tick; neither is answered when the teleport is sent.
            motion(f, new Vec3(0.0, 0.25, 0.0));
            teleport(f, CHANGE, DELTA);
            Vec3 handled = f.player.compensatedEntities.getSelf().deltaMovement;
            assertEquals(0.25, handled.y, 1e-4);
            assertStartsWith(f, handled.add(CHANGE));
        }
    }

    @ParameterizedTest(name = "{0}: a motion before the first ride tick replaces the mounted carry")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aMotionBeforeTheFirstRideTickReplacesTheMountedCarry(String release, ClientVersion version) throws Exception {
        try (var f = mounted(version, 0)) {
            motion(f, new Vec3(0.125, 0.25, -0.125));
            teleport(f, CHANGE, DELTA);
            assertStartsWith(
                    f, f.player.compensatedEntities.getSelf().deltaMovement.add(CHANGE));
        }
    }

    @ParameterizedTest(name = "{0}: a ride tick after send changes the teleport's source motion")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aTeleportReadsTheRideTickBeforeItsProof(String release, ClientVersion version) throws Exception {
        try (var f = mounted(version)) {
            motion(f, new Vec3(0.0, 0.25, 0.0));
            f.pongThrough(f.player.lastTransactionSent.get());
            sendTeleport(f, CHANGE, DELTA);
            // This tick ran before the client received the teleport and its trailing ping.
            assertVehicleExact(f.rideTick(BOAT_AT, true), 0);
            f.pongThrough(f.player.lastTransactionSent.get());
            assertStartsWith(f, RIDE_TICK.add(CHANGE));
        }
    }

    @ParameterizedTest(name = "{0}: an absolute teleport velocity ignores the passenger")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void anAbsoluteTeleportVelocityIsTheTeleportsOwn(String release, ClientVersion version) throws Exception {
        try (var f = mounted(version)) {
            teleport(f, CHANGE, EnumSet.noneOf(Relative.class));
            assertStartsWith(f, CHANGE);
        }
    }

    private static float landFriction() {
        float sum = 0.0F;
        for (int block = 0; block < 6; block++) sum += 0.6F;
        return sum / 6;
    }

    private static void assertStartsWith(JavaTickFixture f, Vec3 velocity) {
        var boat = f.player.compensatedEntities.getEntity(BOAT);
        assertVec(velocity, boat.deltaMovement);
        var starting = f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels();
        assertEquals(1, starting.size(), "candidates " + starting);
        assertVec(velocity, starting.iterator().next());
    }

    private static void assertVec(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, 1e-12, "x of " + actual);
        assertEquals(expected.y, actual.y, 1e-12, "y of " + actual);
        assertEquals(expected.z, actual.z, 1e-12, "z of " + actual);
    }

    private static JavaTickFixture mounted(ClientVersion version) throws Exception {
        return mounted(version, 6);
    }

    private static JavaTickFixture mounted(ClientVersion version, int settleTicks) throws Exception {
        var f = new JavaTickFixture();
        boolean ready = false;
        try {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.place(BOAT_AT, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, BOAT_AT, 0, 0, 0);
            f.player.compensatedEntities.getEntity(BOAT).onGround = true;
            f.setPassengers(BOAT, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.input(NONE);
            for (int tick = 0; tick < settleTicks; tick++) assertVehicleExact(f.rideTick(BOAT_AT, true), tick);
            ready = true;
            return f;
        } finally {
            if (!ready) f.close();
        }
    }

    /** A set_entity_motion for the local passenger, left unanswered. */
    private static void motion(JavaTickFixture f, Vec3 velocity) {
        var packet = new ClientboundEntityMotion(f.player.entityID, new Vec3d(velocity.x, velocity.y, velocity.z));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_MOTION, packet, false);
        f.player.packetEntityReplication.onSetEntityMotion(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
    }

    /** A teleport of the boat to where it is, with {@code delta} under {@code relatives}, answered by its proof. */
    private static void teleport(JavaTickFixture f, Vec3 delta, Set<Relative> relatives) {
        sendTeleport(f, delta, relatives);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void sendTeleport(JavaTickFixture f, Vec3 delta, Set<Relative> relatives) {
        var packet = new ClientboundTeleportEntity(
                BOAT,
                new Vec3d(BOAT_AT.x, BOAT_AT.y, BOAT_AT.z),
                new Vec3d(delta.x, delta.y, delta.z),
                0,
                0,
                relatives,
                true);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.TELEPORT_ENTITY, packet, false);
        f.player.packetEntityReplication.onTeleportEntity(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
    }
}
