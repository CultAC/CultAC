package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A 1.21.11 client crouch-jumps from a floor three blocks below a ceiling. The crouching box clears the ceiling at
 * the top of the jump while a standing box would not, so Entity#move never clips the rise and the jump peaks and
 * falls under gravity alone. The heights are a real client's, at the coordinates it sent them from.
 */
class JavaCrouchJumpUnderCeilingTest {
    private static final ServerboundPlayerInput CROUCH =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);
    private static final ServerboundPlayerInput CROUCH_JUMP =
            new ServerboundPlayerInput(false, false, false, false, true, true, false);
    private static final double X = 36.30000001192093;
    private static final double Z = 101.30000001192093;
    private static final int FLOOR = 63;
    private static final int CEILING = 66;
    private static final double CRAWL_X = 253.30000001192093;
    private static final double CRAWL_Z = -3.699999988079071;
    private static final int CRAWL_FLOOR = 48;
    private static final double KNOCKBACK_Y = 0.36080083012879194;
    private static final double STANDING_X = -834.5171203613281;
    private static final double STANDING_Z = -735.2959594726562;
    private static final int STANDING_FLOOR = 63;
    /** The capture's floor, 8 above {@link JavaTickFixture#FLOOR_Y} in the same binade, so the shift is exact. */
    private static final double CAPTURE_FLOOR = FLOOR_Y + 8;
    private static final double GRID_X = 0.138793275865;
    private static final double GRID_Z = 0.5;
    /** The recorded heights a jump takes under a ceiling at its floor + 3. */
    private static final double[] HEIGHTS = {
        72.41999998688698,
        72.7531999805212,
        73.00133597911214,
        73.16610926093821,
        73.24918707874468,
        73.25220334025373,
        73.17675927506424,
        73.02442408821369,
    };
    /** The tick whose player_input releases jump. */
    private static final int JUMP_RELEASE = 5;

    @Test
    void aCrouchedJumpThatClearsTheCeilingKeepsItsRise() throws Exception {
        try (var f = crouchedUnder(CEILING)) {
            riseToTheTopUnderTheCeiling(f);
            check(f, "peak", 64.25220334025373, false);
            check(f, "F1", 64.17675927506424, false);
            check(f, "F2", 64.02442408821369, false);
        }
    }

    /** Nothing under the crouching box at the top of the jump: a ground claim there is spoofed. */
    @Test
    void aGroundClaimAtTheTopOfTheJumpStillFlags() throws Exception {
        try (var f = crouchedUnder(CEILING)) {
            riseToTheTopUnderTheCeiling(f);
            assertRejected(f.tick(new Vec3(X, 64.25220334025373, Z), true), "GroundSpoof");
        }
    }

    /**
     * Under a ceiling two blocks up the crouching box itself hits the ceiling on the second rising tick, so
     * Block#updateEntityMovementAfterFallOn zeroes the rise. A client that stays pressed to the ceiling afterwards
     * keeps a rise vanilla took away.
     */
    @Test
    void aCrouchedJumpThatHitsTheCeilingCannotHoverUnderIt() throws Exception {
        try (var f = crouchedUnder(FLOOR + 2)) {
            check(f, "J1", FLOOR + 0.41999998688698, false);
            double pressed = FLOOR + 2 - (double) 1.5F;
            check(f, "clipped", pressed, false);
            assertRejected(f.tick(new Vec3(X, pressed, Z), false), "VerticalOffset");
        }
    }

    /**
     * A crawling client in a one-block gap, knocked up from the floor: the 0.6-high box rises unclipped by the
     * knockback and meets the ceiling on the next tick. Heights from a real client.
     */
    @Test
    void aCrawlerKnockedUpUnderACeilingMeetsItTheNextTick() throws Exception {
        try (var f = crawlingUnderACeiling()) {
            f.pongThrough(f.velocity(new Vec3(0, KNOCKBACK_Y, 0)));
            crawl(f, "knocked", 48.360800830128795, false);
            crawl(f, "ceiling", 48.39999997615814, false);
            crawl(f, "F1", 48.321599974632264, false);
            crawl(f, "F2", 48.166367970115665, false);
            crawl(f, "floor", CRAWL_FLOOR, true);
        }
    }

    /** The crawler's box meets the ceiling on the second tick, which zeroes the rise: staying pressed to it flags. */
    @Test
    void aCrawlerPressedToTheCeilingAfterKnockbackStillFlags() throws Exception {
        try (var f = crawlingUnderACeiling()) {
            f.pongThrough(f.velocity(new Vec3(0, KNOCKBACK_Y, 0)));
            crawl(f, "knocked", 48.360800830128795, false);
            crawl(f, "ceiling", 48.39999997615814, false);
            assertRejected(f.tick(new Vec3(CRAWL_X, 48.39999997615814, CRAWL_Z), false), "VerticalOffset");
        }
    }

    /** At the ceiling the crawler's box meets the gap's top, not its floor: a ground claim there is spoofed. */
    @Test
    void aCrawlerClaimingGroundAtTheCeilingStillFlags() throws Exception {
        try (var f = crawlingUnderACeiling()) {
            f.pongThrough(f.velocity(new Vec3(0, KNOCKBACK_Y, 0)));
            crawl(f, "knocked", 48.360800830128795, false);
            assertRejected(f.tick(new Vec3(CRAWL_X, 48.39999997615814, CRAWL_Z), true), "GroundSpoof");
        }
    }

    /**
     * A setback into the floor's top block, then a jump on a tick that sends no position: the engine accepts a rise
     * past the jump's 0.42, which Entity#collide cannot lengthen into, so it is no clip of the jump, and the client
     * that stays where it is afterwards carries no rise. Positions, inputs and rotations from a real client.
     */
    @Test
    void aRiseLongerThanTheJumpCarriesNoJumpVelocity() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11);
                var ignored = JavaTickFixture.observeOnly()) {
            f.player.compensatedWorld.ensureValidationChunkLoaded(-53, -46);
            f.player.compensatedWorld.ensureValidationChunkLoaded(-53, -47);
            for (int x = -837; x <= -833; x++) {
                for (int z = -737; z <= -733; z++) {
                    f.player.compensatedWorld.updateBlock(
                            new BlockPos(x, STANDING_FLOOR - 1, z), BlockIds.STONE.defaultState());
                }
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 setback = new Vec3(-834.3350948955106, 62.48687499891035, -734.8960140180537);
            Vec3 floor = new Vec3(STANDING_X, STANDING_FLOOR, STANDING_Z);
            f.place(floor, Vec3.ZERO);
            f.player.onGround = f.player.lastOnGround = true;
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
            f.player.yRot = f.player.lastTickYRot = 2719.457F;
            f.player.xRot = f.player.lastTickXRot = 12.131311F;
            check(f, "floor", floor, true);
            f.teleport(setback, new RelativeFlag(0));
            f.input(new ServerboundPlayerInput(true, false, true, false, true, false, false));
            assertRejected(rotationTick(f, 2726.071F, 11.777883F), "VerticalOffset");
            f.input(new ServerboundPlayerInput(true, false, false, true, true, false, false));
            float[][] rotations = {
                {2791.5312F, 20.638859F}, {2839.345F, 22.835169F}, {2842.5513F, 23.542028F}, {2842.5513F, 23.542028F}
            };
            for (int tick = 0; tick < rotations.length; tick++) {
                PredictionResult still = rotationTick(f, rotations[tick][0], rotations[tick][1]);
                assertTrue(
                        still.getFlags().stream()
                                .map(flag -> flag.getCheck().getCheckName())
                                .noneMatch(name -> name.equals("VerticalOffset") || name.equals("Simulation")),
                        "still " + tick + ": " + describe(still));
            }
        }
    }

    /** A client tick that sends only its rotation, not on the ground; the prediction it committed. */
    private static PredictionResult rotationTick(JavaTickFixture f, float yaw, float pitch) {
        var packet = new ServerboundMovePlayer(0, 0, 0, yaw, pitch, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, packet), f.player, packet);
        f.tickEnd();
        return f.player.checkManager.getSimulationProcessor().getLastPrediction();
    }

    @Test
    void aCrouchingJumpUnderAThreeBlockCeilingKeepsItsArc() throws Exception {
        try (var f = sneakingUnder(3)) {
            for (int tick = 0; tick < HEIGHTS.length; tick++) {
                if (tick == JUMP_RELEASE) f.input(CROUCH);
                PredictionResult result = f.tick(at(HEIGHTS[tick]), false);
                assertTrue(exact(result), "tick " + tick + ": " + describe(result));
            }
        }
    }

    @Test
    void aCrouchingJumpCannotHoldItsApexAgainstTheCeiling() throws Exception {
        try (var f = sneakingUnder(3)) {
            for (int tick = 0; tick < JUMP_RELEASE; tick++) {
                assertTrue(exact(f.tick(at(HEIGHTS[tick]), false)), "tick " + tick);
            }
            f.input(CROUCH);
            assertRejected(f.tick(at(HEIGHTS[JUMP_RELEASE - 1]), true), "GroundSpoof");
        }
    }

    @Test
    void aCrouchingHeadHitStillStopsTheRise() throws Exception {
        try (var f = sneakingUnder(2)) {
            assertTrue(exact(f.tick(new Vec3(GRID_X, FLOOR_Y + 0.41999998688697815, GRID_Z), false)), "jump");
            Vec3 headHit = new Vec3(GRID_X, FLOOR_Y + 0.5, GRID_Z);
            assertTrue(exact(f.tick(headHit, false)), "head hit");
            f.input(CROUCH);
            assertRejected(f.tick(headHit, false), "VerticalOffset");
        }
    }

    @Test
    void aCrouchingHeadHitFallsFromRest() throws Exception {
        try (var f = sneakingUnder(2)) {
            assertTrue(exact(f.tick(new Vec3(GRID_X, FLOOR_Y + 0.41999998688697815, GRID_Z), false)), "jump");
            assertTrue(exact(f.tick(new Vec3(GRID_X, FLOOR_Y + 0.5, GRID_Z), false)), "head hit");
            f.input(CROUCH);
            PredictionResult fall = f.tick(new Vec3(GRID_X, FLOOR_Y + 0.5 - 0.0784000015258789, GRID_Z), false);
            assertTrue(exact(fall), describe(fall));
        }
    }

    /**
     * A 26.3-server client of version 1.21.11 sneaking still for a tick on the fixture floor under a ceiling
     * {@code ceiling} blocks up, so Player#updatePlayerPose has crouched it, then holding jump.
     */
    private static JavaTickFixture sneakingUnder(int ceiling) throws Exception {
        JavaTickFixture f = new JavaTickFixture(ProtocolVersion.V26_3, ClientVersion.V_1_21_11);
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                f.player.compensatedWorld.updateBlock(
                        new BlockPos(x, FLOOR_Y + ceiling, z), BlockIds.STONE.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        Vec3 start = new Vec3(GRID_X, FLOOR_Y, GRID_Z);
        f.place(start, Vec3.ZERO);
        f.input(CROUCH);
        assertTrue(exact(f.tick(start, true)));
        assertEquals(Pose.CROUCHING, f.player.pose);
        f.input(CROUCH_JUMP);
        return f;
    }

    private static Vec3 at(double captureY) {
        return new Vec3(GRID_X, captureY - CAPTURE_FLOOR + FLOOR_Y, GRID_Z);
    }

    /** A crawling client on the floor of a one-block gap, holding shift. */
    private static JavaTickFixture crawlingUnderACeiling() throws Exception {
        var f = new JavaTickFixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11);
        f.player.compensatedWorld.ensureValidationChunkLoaded(
                (int) Math.floor(CRAWL_X) >> 4, (int) Math.floor(CRAWL_Z) >> 4);
        for (int x = 251; x <= 255; x++) {
            for (int z = -6; z <= -2; z++) {
                f.player.compensatedWorld.updateBlock(
                        new BlockPos(x, CRAWL_FLOOR - 1, z), BlockIds.STONE.defaultState());
                f.player.compensatedWorld.updateBlock(
                        new BlockPos(x, CRAWL_FLOOR + 1, z), BlockIds.STONE.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.player.pose = Pose.SWIMMING;
        f.place(new Vec3(CRAWL_X, CRAWL_FLOOR, CRAWL_Z), Vec3.ZERO);
        f.player.onGround = f.player.lastOnGround = true;
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
        f.input(CROUCH);
        crawl(f, "crawl", CRAWL_FLOOR, true);
        return f;
    }

    private static void crawl(JavaTickFixture f, String name, double y, boolean onGround) {
        check(f, name, new Vec3(CRAWL_X, y, CRAWL_Z), onGround);
    }

    /** A crouching client on the floor holding jump, under a ceiling at {@code ceiling}. */
    private static JavaTickFixture crouchedUnder(int ceiling) throws Exception {
        var f = new JavaTickFixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11);
        f.player.compensatedWorld.ensureValidationChunkLoaded((int) X >> 4, (int) Z >> 4);
        for (int x = 34; x <= 38; x++) {
            for (int z = 99; z <= 103; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR - 1, z), BlockIds.STONE.defaultState());
                f.player.compensatedWorld.updateBlock(new BlockPos(x, ceiling, z), BlockIds.STONE.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        Vec3 floor = new Vec3(X, FLOOR, Z);
        f.place(floor, Vec3.ZERO);
        f.player.onGround = f.player.lastOnGround = true;
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
        f.input(CROUCH);
        check(f, "crouch", floor, true);
        f.input(CROUCH_JUMP);
        return f;
    }

    /** The client's rising ticks, up to the last one a standing box could not make under the ceiling. */
    private static void riseToTheTopUnderTheCeiling(JavaTickFixture f) {
        check(f, "J1", FLOOR + 0.41999998688698, false);
        check(f, "J2", 63.7531999805212, false);
        check(f, "J3", 64.00133597911214, false);
        check(f, "J4", 64.16610926093821, false);
        check(f, "J5", 64.24918707874468, false);
    }

    private static void check(JavaTickFixture f, String name, double y, boolean onGround) {
        check(f, name, new Vec3(X, y, Z), onGround);
    }

    private static void check(JavaTickFixture f, String name, Vec3 to, boolean onGround) {
        PredictionResult result = f.tick(to, onGround);
        assertTrue(exact(result), name + ": " + describe(result));
    }
}
