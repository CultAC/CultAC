package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Entity#move scales the move by the stuck multiplier and zeroes deltaMovement in the same branch, and
 * LivingEntity#aiStep sets the multiplier for the next move only after travel. A stuck block that appears after a
 * move can slow a later move; it cannot give the next move a zero start.
 */
class JavaStuckSpeedBlockChangeTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final double DRAG = 0.98F;
    private static final double WEB_Y = 0.05F;
    private static final double WEB_X = 0.25;
    // The box edge sits 2e-5 inside the web column: inside the web by 1e-5 past Entity#checkInsideBlocks' deflation.
    private static final double WEB_EDGE_X = 1.0 - 0.3 + 2.0E-5;
    private static final double EDGE_Y = 90;
    // The box edge sits 5e-6 inside the web column: outside the web by Entity#checkInsideBlocks' 1e-5 deflation.
    private static final double EDGE_X = 1.0 - 0.3 + 5.0E-6;
    private static final BlockPos INSIDE = new BlockPos(0, 89, 0);

    private static double fall(double velocity) {
        return (velocity - 0.08) * DRAG;
    }

    private static void version(JavaTickFixture f, ClientVersion version) throws Exception {
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
    }

    private static void predictWeb(JavaTickFixture f, BlockPos pos) {
        CompensatedWorld world = f.player.compensatedWorld;
        world.advanceClientPredictionSequence(world.clientPredictionSequence() + 1);
        world.startPredicting();
        world.updateBlock(pos, BlockIds.COBWEB.defaultState());
        world.stopPredicting(world.clientPredictionSequence());
    }

    /** A server block update and its trailing ping, unanswered. */
    private static int sendBlock(JavaTickFixture f, BlockPos pos, int state) {
        int transaction = f.player.sendTrailingTransaction();
        f.player.compensatedWorld.queueServerBlockUpdate(pos.getX(), pos.getY(), pos.getZ(), state, transaction);
        return transaction;
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }

    private static PredictionResult hoverAfterFastFall(JavaTickFixture f, ClientVersion version, boolean web)
            throws Exception {
        version(f, version);
        f.place(new Vec3(EDGE_X, EDGE_Y, 0.5), new Vec3(0, -1.0, 0));
        f.input(NONE);
        assertExact(f.tick(new Vec3(EDGE_X, EDGE_Y - 1.0, 0.5), false));
        if (web) predictWeb(f, new BlockPos(1, (int) EDGE_Y - 1, 0));
        return f.tick(new Vec3(EDGE_X, EDGE_Y - 1.0, 0.5), false);
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_2", "V_1_21_4", "V_1_21_5", "V_1_21_7"})
    void aWebBesideTheLastMoveGivesTheNextMoveNoZeroStart(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            PredictionResult hover = hoverAfterFastFall(f, version, true);
            assertFalse(exact(hover), describe(hover));
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_26_3"})
    void theSameHoverWithoutAWebIsRejected(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            PredictionResult hover = hoverAfterFastFall(f, version, false);
            assertFalse(exact(hover), describe(hover));
        }
    }

    @Test
    void theExactEffectsCarryRejectsTheHoverBesideAPlacedWeb() throws Exception {
        try (var f = new JavaTickFixture()) {
            PredictionResult hover = hoverAfterFastFall(f, ClientVersion.V_26_3, true);
            assertFalse(exact(hover), describe(hover));
        }
    }

    /** Falls one block at -1 b/t into the web cell; the web lands through {@code web}, then the follow-up moves. */
    private static double fallIntoWeb(JavaTickFixture f, ClientVersion version) throws Exception {
        version(f, version);
        f.place(new Vec3(0.5, 90, 0.5), new Vec3(0, -1.0, 0));
        f.input(NONE);
        assertExact(f.tick(new Vec3(0.5, 89, 0.5), false));
        return 89;
    }

    /** The move after the web arrived is not slowed; the one after it is, from the carried velocity. */
    private static void unslowedThenStuck(JavaTickFixture f, double y) {
        double v1 = fall(-1.0);
        y += v1;
        assertExact(f.tick(new Vec3(0.5, y, 0.5), false));
        double v2 = fall(v1);
        y += v2 * WEB_Y;
        assertExact(f.tick(new Vec3(0.5, y, 0.5), false));
        y += fall(0) * WEB_Y;
        assertExact(f.tick(new Vec3(0.5, y, 0.5), false));
    }

    /** The move after the web arrived is slowed; the next starts from zero and is slowed again. */
    private static void stuckThenFromZero(JavaTickFixture f, double y) {
        y += fall(-1.0) * WEB_Y;
        assertExact(f.tick(new Vec3(0.5, y, 0.5), false));
        y += fall(0) * WEB_Y;
        assertExact(f.tick(new Vec3(0.5, y, 0.5), false));
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_2", "V_1_21_4", "V_1_21_7"})
    void aWebPlacedIntoTheLastMoveSlowsTheMoveAfterTheNext(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            double y = fallIntoWeb(f, version);
            predictWeb(f, INSIDE);
            unslowedThenStuck(f, y);
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_2", "V_1_21_4", "V_1_21_7"})
    void aWebPlacedIntoTheLastMoveMaySlowTheNextMove(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            double y = fallIntoWeb(f, version);
            predictWeb(f, INSIDE);
            stuckThenFromZero(f, y);
        }
    }

    /**
     * A player whose box reaches 1e-5 into a web column past Entity#checkInsideBlocks' deflation, pushed toward it by
     * another player after each travel (Entity#push): every move is the pushed start slowed by the web, and each next
     * move starts from zero plus the next push.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_2", "V_1_21_4", "V_1_21_7", "V_1_21_11"})
    void aPushedPlayerAtAWebEdgeStartsEachMoveFromZeroAndThePush(ClientVersion version) throws Exception {
        try (var f = webEdge(version)) {
            double x = WEB_EDGE_X;
            double other = x - 0.3;
            f.addRemotePlayer(500, new Vec3(other, 90, 0.5), GameMode.SURVIVAL);
            double vx = push(x, other);
            double vy = fall(0);
            f.place(new Vec3(x, 90, 0.5), new Vec3(vx, vy, 0));
            f.input(NONE);
            double y = 90;
            boolean stuck = false;
            for (int tick = 0; tick < 6; tick++) {
                if (Math.abs(vx) < 0.003) vx = 0;
                if (Math.abs(vy) < 0.003) vy = 0;
                x += stuck ? vx * WEB_X : vx;
                y += stuck ? vy * WEB_Y : vy;
                if (stuck) vx = vy = 0;
                vx *= (double) 0.91F;
                vy = fall(vy);
                vx += push(x, other);
                stuck = true;
                PredictionResult result = f.tick(new Vec3(x, y, 0.5), false);
                assertTrue(exact(result), "tick " + tick + ": " + describe(result));
            }
        }
    }

    /**
     * A player standing still at a web edge, sending no positions, until another player arrives beside it: from the
     * tick after the first push each move is that push slowed by the web, from a zero start.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_2", "V_1_21_4", "V_1_21_7", "V_1_21_11"})
    void aStillPlayerAtAWebEdgeMovesByEachPushSlowed(ClientVersion version) throws Exception {
        try (var f = webEdge(version)) {
            double x = 1.0 - 0.3 + 1.0E-4;
            double y = 85;
            for (int z = -1; z <= 1; z++) {
                for (int bx = -1; bx <= 2; bx++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(bx, 84, z), BlockIds.STONE.defaultState());
                }
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            f.place(new Vec3(x, y, 0.5), new Vec3(0, fall(0), 0));
            f.input(NONE);
            for (int tick = 0; tick < 3; tick++) f.statusTick(true, false);
            double other = x - 0.3;
            f.addRemotePlayer(500, new Vec3(other, y, 0.5), GameMode.SURVIVAL);
            f.statusTick(true, false);
            double vx = push(x, other);
            for (int tick = 0; tick < 4; tick++) {
                x += vx * WEB_X;
                vx = push(x, other);
                PredictionResult result = f.tick(new Vec3(x, y, 0.5), true);
                assertTrue(exact(result), "tick " + tick + ": " + describe(result));
            }
        }
    }

    /**
     * Knockback on a player stuck at a web edge, proven before a move or handled after a move its ping straddles: the
     * web slows the knocked move, and the move after it starts from zero.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_2", "V_1_21_4", "V_1_21_7", "V_1_21_11"})
    void aKnockbackSlowedAtAWebEdgeStartsTheNextMoveFromZero(ClientVersion version) throws Exception {
        knockbackAtWebEdge(version, false, false);
        knockbackAtWebEdge(version, true, true);
    }

    /**
     * The knockback proven before the knocked move ({@code split} false), or its ping split around a move and the
     * client handling it before ({@code late} false) or after that move.
     */
    private static void knockbackAtWebEdge(ClientVersion version, boolean split, boolean late) throws Exception {
        try (var f = webEdge(version)) {
            f.place(new Vec3(WEB_EDGE_X, 90, 0.5), new Vec3(0, fall(0), 0));
            f.input(NONE);
            Vec3 at = new Vec3(WEB_EDGE_X, 90 + fall(0), 0.5);
            PredictionResult entering = f.tick(at, false);
            assertTrue(exact(entering), "entering: " + describe(entering));
            double vy = fall(fall(0));
            for (int tick = 0; tick < 2; tick++) {
                at = at.add(0, vy * WEB_Y, 0);
                vy = fall(0);
                PredictionResult stuck = f.tick(at, false);
                assertTrue(exact(stuck), "stuck " + tick + ": " + describe(stuck));
            }
            Vec3 knockback = new Vec3(0.3, 0.2, -0.1);
            int proof = f.velocity(knockback);
            f.pongThrough(proof - (split ? 1 : 0));
            if (late) {
                at = at.add(0, fall(0) * WEB_Y, 0);
                PredictionResult before = f.tick(at, false);
                assertTrue(exact(before), "before the knockback: " + describe(before));
                f.pongThrough(proof);
            }
            at = at.add(knockback.x * WEB_X, knockback.y * WEB_Y, knockback.z * WEB_X);
            PredictionResult slowed = f.tick(at, false);
            assertTrue(exact(slowed), "slowed knockback: " + describe(slowed));
            f.pongThrough(proof);
            PredictionResult fromZero = f.tick(at.add(0, fall(0) * WEB_Y, 0), false);
            assertTrue(exact(fromZero), "from zero: " + describe(fromZero));
        }
    }

    /** A cobweb column at x 1, y 80 to 95, z 0. */
    private static JavaTickFixture webEdge(ClientVersion version) throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        version(f, version);
        for (int y = 80; y <= 95; y++) {
            f.player.compensatedWorld.updateBlock(new BlockPos(1, y, 0), BlockIds.COBWEB.defaultState());
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        return f;
    }

    /** Entity#push's x for a player at {@code x} from a player at {@code other} on the same z. */
    private static double push(double x, double other) {
        double dx = x - other;
        double distance = Math.sqrt(Math.abs(dx));
        return dx / distance * Math.min(1.0, 1.0 / distance) * 0.05;
    }

    @Test
    void aServerWebIntoTheLastMoveKeepsBothOutcomesOpen() throws Exception {
        try (var f = new JavaTickFixture()) {
            double y = fallIntoWeb(f, ClientVersion.V_1_21_4);
            f.pongThrough(sendBlock(f, INSIDE, BlockIds.COBWEB.defaultState()));
            unslowedThenStuck(f, y);
        }
        try (var f = new JavaTickFixture()) {
            double y = fallIntoWeb(f, ClientVersion.V_1_21_4);
            f.pongThrough(sendBlock(f, INSIDE, BlockIds.COBWEB.defaultState()));
            stuckThenFromZero(f, y);
        }
    }

    /**
     * The client handles the update with the ping that follows it, three ticks after it was sent: the web takes
     * effect from the moves after that pong, and the fall before it is not slowed.
     */
    @Test
    void aWebProvenThreeMovesLateTakesEffectAfterItsPong() throws Exception {
        for (boolean stuck : new boolean[] {false, true}) {
            try (var f = new JavaTickFixture()) {
                version(f, ClientVersion.V_1_21_4);
                BlockPos column = new BlockPos(0, 80, 0);
                f.place(new Vec3(0.5, 90, 0.5), new Vec3(0, -1.0, 0));
                f.input(NONE);
                int[] proofs = new int[11];
                for (int i = 0; i < proofs.length; i++) {
                    proofs[i] = sendBlock(f, column.above(i), BlockIds.COBWEB.defaultState());
                }
                double y = 90;
                double v = -1.0;
                for (int tick = 0; tick < 3; tick++) {
                    y += v;
                    assertExact(f.tick(new Vec3(0.5, y, 0.5), false));
                    v = fall(v);
                }
                f.pongThrough(proofs[proofs.length - 1]);
                if (stuck) {
                    y += v * WEB_Y;
                    assertExact(f.tick(new Vec3(0.5, y, 0.5), false));
                    y += fall(0) * WEB_Y;
                    assertExact(f.tick(new Vec3(0.5, y, 0.5), false));
                } else {
                    y += v;
                    assertExact(f.tick(new Vec3(0.5, y, 0.5), false));
                    v = fall(v);
                    y += v * WEB_Y;
                    assertExact(f.tick(new Vec3(0.5, y, 0.5), false));
                }
            }
        }
    }

    /** Forty held pongs answered at once after the stall: the web applies in their order, both outcomes stay open. */
    @Test
    void aWebInsideAHeldBurstOfPongsKeepsBothOutcomesOpen() throws Exception {
        for (boolean stuck : new boolean[] {false, true}) {
            try (var f = new JavaTickFixture()) {
                double y = fallIntoWeb(f, ClientVersion.V_1_21_4);
                for (int i = 0; i < 20; i++) f.player.sendTrailingTransaction();
                sendBlock(f, INSIDE, BlockIds.COBWEB.defaultState());
                int last = 0;
                for (int i = 0; i < 20; i++) last = f.player.sendTrailingTransaction();
                f.pongThrough(last);
                if (stuck) {
                    stuckThenFromZero(f, y);
                } else {
                    unslowedThenStuck(f, y);
                }
            }
        }
    }

    /** A web broken under a player it slowed: the move it set the multiplier for is slowed, the next one is not. */
    @Test
    void aWebBrokenUnderAStuckPlayerKeepsItsLastSlowedMove() throws Exception {
        try (var f = new JavaTickFixture()) {
            double y = fallIntoWeb(f, ClientVersion.V_1_21_4);
            predictWeb(f, INSIDE);
            y += fall(-1.0);
            assertExact(f.tick(new Vec3(0.5, y, 0.5), false));
            y += fall(fall(-1.0)) * WEB_Y;
            assertExact(f.tick(new Vec3(0.5, y, 0.5), false));
            f.pongThrough(sendBlock(f, INSIDE, BlockIds.AIR.defaultState()));
            y += fall(0) * WEB_Y;
            assertExact(f.tick(new Vec3(0.5, y, 0.5), false));
            y += fall(0);
            assertExact(f.tick(new Vec3(0.5, y, 0.5), false));
        }
    }

    /**
     * A 1.21.11 client falling past a static web column whose face is 5e-6 beyond its box: the box, deflated by
     * 1e-5, never touches the web, so neither a zero-velocity tick nor a slowed move fits at any fall speed.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_11", "V_26_3"})
    void aFallPastAStaticWebColumnTakesNoZeroStart(ClientVersion version) throws Exception {
        for (int ticks : new int[] {6, 24, 30}) {
            for (boolean slowed : new boolean[] {false, true}) {
                try (var f = new JavaTickFixture()) {
                    version(f, version);
                    for (int y = 65; y <= 105; y++) {
                        f.player.compensatedWorld.updateBlock(new BlockPos(1, y, 0), BlockIds.COBWEB.defaultState());
                    }
                    for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
                    double x = 1.0 - 0.3 - 5.0E-6;
                    double y = 100;
                    double v = 0;
                    f.place(new Vec3(x, y, 0.5), Vec3.ZERO);
                    f.input(NONE);
                    for (int tick = 0; tick < ticks; tick++) {
                        y += v;
                        assertExact(f.tick(new Vec3(x, y, 0.5), false));
                        v = fall(v);
                    }
                    if (slowed) y += v * WEB_Y;
                    PredictionResult cheat = f.tick(new Vec3(x, y, 0.5), false);
                    assertFalse(exact(cheat), ticks + " ticks, slowed " + slowed + ": " + describe(cheat));
                }
            }
        }
    }
}
