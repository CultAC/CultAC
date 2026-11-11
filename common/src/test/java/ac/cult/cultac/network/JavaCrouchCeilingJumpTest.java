package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A 1.21.11, 26.1, 26.2 or 26.3 client holding shift jumps under a ceiling three blocks above the floor. Player#updatePlayerPose keeps it
 * crouching, so Entity#collide moves its 1.5-tall box, which clears the ceiling at the jump's apex where a standing
 * 1.8-tall box would not.
 */
class JavaCrouchCeilingJumpTest {
    private static final ServerboundPlayerInput SNEAK =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);
    private static final ServerboundPlayerInput SNEAK_JUMP =
            new ServerboundPlayerInput(false, false, false, false, true, true, false);
    private static final ServerboundPlayerInput JUMP_ONLY =
            new ServerboundPlayerInput(false, false, false, false, true, false, false);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final double GRAVITY = 0.08D;
    private static final double AIR_DRAG = 0.98F;
    private static final double JUMP = 0.42F;
    private static final float SCALE = 1.7F;

    // The recorded heights of the jump from the floor at 74.0 under the ceiling at 77.0.
    private static final double[] RECORDED_RISE = {
        0.41999998688697815D,
        0.33319999363422426D,
        0.24813599859093927D,
        0.1647732818260721D,
        0.08307781780646906D,
        0.003016261509046103D,
        -0.07544406518948676D
    };

    static Stream<ClientVersion> versions() {
        return Stream.of(ClientVersion.V_1_21_11, ClientVersion.V_26_1, ClientVersion.V_26_2, ClientVersion.V_26_3);
    }

    @ParameterizedTest
    @MethodSource("versions")
    void aCrouchingJumpClearsTheCeilingAtItsApex(ClientVersion version) throws Exception {
        try (var f = client(version)) {
            ceiling(f, FLOOR_Y + 3);
            Vec3 at = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(at, new Vec3(0.0, -GRAVITY * AIR_DRAG, 0.0));
            f.input(SNEAK);
            for (int tick = 0; tick < 3; tick++) {
                PredictionResult standing = f.tick(at, true);
                assertTrue(exact(standing), "standing " + tick + ": " + describe(standing));
            }

            f.input(SNEAK_JUMP);
            double velocity = JUMP;
            for (int tick = 0; tick < RECORDED_RISE.length; tick++) {
                at = at.add(0.0, velocity, 0.0);
                PredictionResult result = f.tick(at, false);
                assertTrue(exact(result), "tick " + tick + " at " + at.y + ": " + describe(result));
                if (tick == 0) f.input(SNEAK);
                velocity = (velocity - GRAVITY) * AIR_DRAG;
            }
        }
    }

    /** A crouching client that stops at the ceiling as a standing box would still flags. */
    @ParameterizedTest
    @MethodSource("versions")
    void aCrouchingJumpStoppedAtStandingHeightFlags(ClientVersion version) throws Exception {
        try (var f = client(version)) {
            ceiling(f, FLOOR_Y + 3);
            Vec3 at = jumpToApex(f, true);
            double clipped = FLOOR_Y + 3 - (double) 1.8F - at.y;
            assertRejected(f.tick(at.add(0.0, clipped, 0.0), false), "VerticalOffset");
        }
    }

    /**
     * A ceiling two blocks above the floor stops the crouching box on the second airborne tick, 1.5 above the feet,
     * and the jump falls from zero.
     */
    @ParameterizedTest
    @MethodSource("versions")
    void aCrouchingJumpStopsWhereTheCrouchingBoxMeetsTheCeiling(ClientVersion version) throws Exception {
        try (var f = client(version)) {
            ceiling(f, FLOOR_Y + 2);
            Vec3 at = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(at, new Vec3(0.0, -GRAVITY * AIR_DRAG, 0.0));
            f.input(SNEAK);
            for (int tick = 0; tick < 3; tick++) {
                PredictionResult standing = f.tick(at, true);
                assertTrue(exact(standing), "standing " + tick + ": " + describe(standing));
            }
            f.input(SNEAK_JUMP);
            at = at.add(0.0, JUMP, 0.0);
            PredictionResult rise = f.tick(at, false);
            assertTrue(exact(rise), "rise: " + describe(rise));
            f.input(SNEAK);
            double clipped = FLOOR_Y + 2 - (double) Pose.CROUCHING.height - at.y;
            at = at.add(0.0, clipped, 0.0);
            PredictionResult hit = f.tick(at, false);
            assertTrue(exact(hit), "hit: " + describe(hit));
            at = at.add(0.0, (0.0 - GRAVITY) * AIR_DRAG, 0.0);
            PredictionResult fall = f.tick(at, false);
            assertTrue(exact(fall), "fall: " + describe(fall));
        }
    }

    /**
     * At scale 1.7 the crouching box is 2.55 tall and clears a ceiling four blocks up at the apex, where the 3.06-tall
     * standing box would stop.
     */
    @ParameterizedTest
    @MethodSource("versions")
    void aScaledCrouchingJumpClearsTheCeilingAtItsApex(ClientVersion version) throws Exception {
        try (var f = client(version)) {
            f.player.compensatedEntities.getSelf().scale = SCALE;
            ceiling(f, FLOOR_Y + 4);
            Vec3 at = jumpToApex(f, true);
            at = at.add(0.0, RECORDED_RISE[4], 0.0);
            PredictionResult apex = f.tick(at, false);
            assertTrue(exact(apex), "apex at " + at.y + ": " + describe(apex));
            at = at.add(0.0, RECORDED_RISE[5], 0.0);
            PredictionResult next = f.tick(at, false);
            assertTrue(exact(next), "after apex at " + at.y + ": " + describe(next));
        }
    }

    /** A scaled crouching client that stops on the third airborne tick, where its standing box meets the ceiling, flags. */
    @ParameterizedTest
    @MethodSource("versions")
    void aScaledCrouchingJumpStoppedAtStandingHeightFlags(ClientVersion version) throws Exception {
        try (var f = client(version)) {
            f.player.compensatedEntities.getSelf().scale = SCALE;
            ceiling(f, FLOOR_Y + 4);
            Vec3 at = rise(f, true, 2);
            double clipped = FLOOR_Y + 4 - (double) (1.8F * SCALE) - at.y;
            assertRejected(f.tick(at.add(0.0, clipped, 0.0), false), "VerticalOffset");
        }
    }

    /** A standing jump under the same ceiling stops at it and falls from zero. */
    @ParameterizedTest
    @MethodSource("versions")
    void aStandingJumpStopsAtTheCeiling(ClientVersion version) throws Exception {
        try (var f = client(version)) {
            ceiling(f, FLOOR_Y + 3);
            Vec3 at = jumpToApex(f, false);
            double clipped = FLOOR_Y + 3 - (double) 1.8F - at.y;
            at = at.add(0.0, clipped, 0.0);
            PredictionResult hit = f.tick(at, false);
            assertTrue(exact(hit), "hit: " + describe(hit));
            at = at.add(0.0, (0.0 - GRAVITY) * AIR_DRAG, 0.0);
            PredictionResult fall = f.tick(at, false);
            assertTrue(exact(fall), "fall: " + describe(fall));
        }
    }

    /** Jumps from the floor and returns where the fifth airborne tick, the first the ceiling can stop, starts. */
    private static Vec3 jumpToApex(JavaTickFixture f, boolean sneaking) {
        return rise(f, sneaking, 4);
    }

    /** Jumps from the floor, plays {@code airborne} unobstructed ticks and returns where the next one starts. */
    private static Vec3 rise(JavaTickFixture f, boolean sneaking, int airborne) {
        Vec3 at = new Vec3(0.5, FLOOR_Y, 0.5);
        f.place(at, new Vec3(0.0, -GRAVITY * AIR_DRAG, 0.0));
        f.input(sneaking ? SNEAK : NONE);
        for (int tick = 0; tick < 3; tick++) {
            PredictionResult standing = f.tick(at, true);
            assertTrue(exact(standing), "standing " + tick + ": " + describe(standing));
        }
        f.input(sneaking ? SNEAK_JUMP : JUMP_ONLY);
        double velocity = JUMP;
        for (int tick = 0; tick < airborne; tick++) {
            at = at.add(0.0, velocity, 0.0);
            PredictionResult result = f.tick(at, false);
            assertTrue(exact(result), "tick " + tick + " at " + at.y + ": " + describe(result));
            if (tick == 0) f.input(sneaking ? SNEAK : NONE);
            velocity = (velocity - GRAVITY) * AIR_DRAG;
        }
        return at;
    }

    @Test
    void theRecordedRiseIsAnUnclippedJump() {
        double velocity = JUMP;
        for (int tick = 0; tick < RECORDED_RISE.length; tick++) {
            assertTrue(Math.abs(velocity - RECORDED_RISE[tick]) < 1.0E-12, "tick " + tick + ": " + velocity);
            velocity = (velocity - GRAVITY) * AIR_DRAG;
        }
    }

    private static void ceiling(JavaTickFixture f, int y) {
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.STONE.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    private static JavaTickFixture client(ClientVersion version) throws Exception {
        if (version == ClientVersion.V_1_21_11) return new JavaTickFixture(ProtocolVersion.V1_21_11, version);
        if (version == ClientVersion.V_26_1) return new JavaTickFixture(ProtocolVersion.V26_1, version);
        if (version == ClientVersion.V_26_2) return new JavaTickFixture(ProtocolVersion.V26_2, version);
        return new JavaTickFixture(ProtocolVersion.V26_3, version);
    }
}
