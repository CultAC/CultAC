package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.NoFallPseudo;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A bounded deltaMovement runs Entity#move on each end that can collide, so a range never hides a landing. */
class JavaVelocityRangeTest {
    private static final double FALL = -0.0784000015258789;
    private static final Vec3 START = new Vec3(0.65, FLOOR_Y, 0.5);

    private static Vec3 ranged(Vec3 velocity, SimpleCollisionBox offsets) {
        return new PredVector(velocity).withVelocityRange(offsets);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aStillReportOnAFloorAcceptsEitherGroundClaimWhenTheRangeAllowsBoth(boolean onGround) throws Exception {
        try (var f = new JavaTickFixture()) {
            // Y from FALL - 0.1 (lands on the floor) to FALL + 0.1 (can hold still without touching it).
            f.place(START, ranged(new Vec3(0, FALL, 0), new SimpleCollisionBox(0, -0.1, 0, 0, 0.1, 0)));
            var result = f.tick(START, onGround);
            assertTrue(JavaTickFixture.exact(result), JavaTickFixture.describe(result));
        }
    }

    @Test
    void anExactFallStillRejectsAFalseGroundClaimOnTheFloor() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(START, new Vec3(0, FALL, 0));
            var result = f.tick(START, false);
            assertTrue(result.hasFlag(NoFallPseudo.class), JavaTickFixture.describe(result));
        }
    }

    @Test
    void aRangeThatCannotReachTheFloorCannotClaimGround() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 start = START.add(0, 1, 0);
            f.place(start, ranged(new Vec3(0, 0, 0), new SimpleCollisionBox(0, -0.05, 0, 0, 0.05, 0)));
            var result = f.tick(start, true);
            assertTrue(result.hasFlag(NoFallPseudo.class), JavaTickFixture.describe(result));
        }
    }

    @Test
    void aMoveBeyondTheRangeStillFlags() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 start = START.add(0, 1, 0);
            f.place(start, ranged(new Vec3(0, 0, 0), new SimpleCollisionBox(0, -0.01, 0, 0, 0.01, 0)));
            var result = f.tick(start.add(0, 0.1, 0), false);
            assertFalse(JavaTickFixture.exact(result), JavaTickFixture.describe(result));
        }
    }

    @Test
    void aRangedMoveIntoASlabStillSteps() throws Exception {
        try (var f = new JavaTickFixture()) {
            int slab = DataTables.defaults().registry().block("minecraft:stone_slab").defaultState();
            f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y, 0), slab);
            f.place(START, ranged(new Vec3(0.1, FALL, 0), new SimpleCollisionBox(-0.02, -0.01, 0, 0.02, 0.01, 0)));
            var result = f.tick(START.add(0.1, 0.5, 0), true);
            assertTrue(JavaTickFixture.exact(result), JavaTickFixture.describe(result));
        }
    }

    @Test
    void theNextTickCarriesTheHullOfTheMatchingEnds() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(START, ranged(new Vec3(0, FALL, 0), new SimpleCollisionBox(0, -0.1, 0, 0, 0.1, 0)));
            assertTrue(JavaTickFixture.exact(f.tick(START, true)));
            // Landed (carry FALL) and held still above the floor (carry FALL as well): one exact next tick either way.
            var next = f.tick(START, true);
            assertTrue(JavaTickFixture.exact(next), JavaTickFixture.describe(next));
        }
    }
}
