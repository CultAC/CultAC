package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A 26.3 client falling in open air, then a climbable changing near it. LivingEntity#travel reads onClimbable at the
 * feet block (and, for an open trapdoor, the block below) after its move, so only a change there can belong to the
 * movement already sent; a client's own placement runs in Minecraft#handleKeybinds before its next travel.
 */
class JavaClimbableBlockChangeTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final double FALL = 0.0784000015258789;
    // The rise of a tick after LivingEntity#handleOnClimbable's 0.2 climb, after gravity and drag.
    private static final double CLIMB = (0.2 - 0.08) * (double) 0.98F;
    private static final double Y = 70;

    /** A ladder beside the feet, placed by the client or the server, lends the fall no climb. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aLadderBesideTheFeetLendsNoClimb(boolean clientPredicted) throws Exception {
        try (var f = new JavaTickFixture()) {
            fall(f);
            change(f, new BlockPos(1, 70, 0), clientPredicted);
            PredictionResult result = f.tick(new Vec3(0.5, Y - FALL + CLIMB, 0.5), false);
            assertFalse(exact(result), describe(result));
        }
    }

    /** A ladder the client places at its own feet only climbs from its next travel on. */
    @Test
    void aLadderTheClientPlacesAtItsFeetLendsTheLastFallNoClimb() throws Exception {
        try (var f = new JavaTickFixture()) {
            fall(f);
            change(f, new BlockPos(0, 69, 0), true);
            PredictionResult result = f.tick(new Vec3(0.5, Y - FALL + CLIMB, 0.5), false);
            assertFalse(exact(result), describe(result));
        }
    }

    /** A ladder the server sets at the feet may have reached the client before the movement it answered. */
    @Test
    void aLadderTheServerSetsAtTheFeetKeepsTheClimbPossible() throws Exception {
        try (var f = new JavaTickFixture()) {
            fall(f);
            change(f, new BlockPos(0, 69, 0), false);
            PredictionResult result = f.tick(new Vec3(0.5, Y - FALL + CLIMB, 0.5), false);
            assertTrue(exact(result), describe(result));
        }
    }

    /**
     * A ladder column the server removes under a client sliding down it: the client climbs until it handles the
     * change, which its pong proves, and falls freely from the next travel on, whether the pong is prompt or a second
     * late.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 20})
    void aLadderTheServerRemovesUnderAClimberHoldsItUntilThePong(int pendingTicks) throws Exception {
        try (var f = new JavaTickFixture()) {
            for (int y = 60; y <= 75; y++) {
                f.player.compensatedWorld.updateBlock(column(y), BlockIds.LADDER.defaultState());
            }
            settle(f);
            Climber climber = new Climber(f, true);
            for (int tick = 0; tick < 4; tick++) climber.assertExact();
            int transaction = f.player.sendTrailingTransaction();
            for (int y = 60; y <= 75; y++) {
                f.player.compensatedWorld.queueServerBlockUpdate(0, y, 0, BlockIds.AIR.defaultState(), transaction);
            }
            for (int tick = 0; tick < pendingTicks; tick++) climber.assertExact();
            f.pongThrough(transaction);
            climber.climbing = false;
            for (int tick = 0; tick < 3; tick++) climber.assertExact();
        }
    }

    /** A ladder column the server places around a falling client only clamps the fall from the client's pong on. */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 20})
    void aLadderTheServerPlacesAroundAFallClampsItFromThePong(int pendingTicks) throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedWorld.updateBlock(column(JavaTickFixture.FLOOR_Y - 1), BlockIds.AIR.defaultState());
            settle(f);
            Climber climber = new Climber(f, false);
            climber.assertExact();
            int transaction = f.player.sendTrailingTransaction();
            for (int y = 40; y <= 75; y++) {
                f.player.compensatedWorld.queueServerBlockUpdate(0, y, 0, BlockIds.LADDER.defaultState(), transaction);
            }
            for (int tick = 0; tick < pendingTicks; tick++) climber.assertExact();
            f.pongThrough(transaction);
            climber.climbing = true;
            for (int tick = 0; tick < 3; tick++) climber.assertExact();
        }
    }

    /** A still client in the column at x = z = 0, one vanilla LivingEntity#travelInAir at a time. */
    private static final class Climber {
        private final JavaTickFixture f;
        private Vec3 position = new Vec3(0.5, 72, 0.5);
        private double velocityY;
        boolean climbing;

        Climber(JavaTickFixture f, boolean climbing) {
            this.f = f;
            this.climbing = climbing;
            f.place(position, Vec3.ZERO);
            f.input(NONE);
        }

        void assertExact() {
            // LivingEntity#handleOnClimbable holds the fall at 0.15 before Entity#move.
            double moveY = climbing ? Math.max(velocityY, -0.15F) : velocityY;
            position = position.add(0.0, moveY, 0.0);
            PredictionResult result = f.tick(position, false);
            assertTrue(exact(result), describe(result) + " at " + position);
            velocityY = (moveY - 0.08) * (double) 0.98F;
        }
    }

    private static BlockPos column(int y) {
        return new BlockPos(0, y, 0);
    }

    private static void settle(JavaTickFixture f) {
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    private static void fall(JavaTickFixture f) {
        f.place(new Vec3(0.5, Y, 0.5), new Vec3(0, -FALL, 0));
        f.input(NONE);
        PredictionResult first = f.tick(new Vec3(0.5, Y - FALL, 0.5), false);
        assertTrue(exact(first), describe(first));
    }

    private static void change(JavaTickFixture f, BlockPos pos, boolean clientPredicted) {
        CompensatedWorld world = f.player.compensatedWorld;
        if (!clientPredicted) {
            world.updateBlock(pos, BlockIds.LADDER.defaultState());
            return;
        }
        world.advanceClientPredictionSequence(world.clientPredictionSequence() + 1);
        world.startPredicting();
        world.updateBlock(pos, BlockIds.LADDER.defaultState());
        world.stopPredicting(world.clientPredictionSequence());
    }
}
