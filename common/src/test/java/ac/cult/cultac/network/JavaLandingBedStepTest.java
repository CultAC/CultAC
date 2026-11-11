package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A 1.21.11 player lands on stone and slides into a bed on the next tick. Block#updateEntityMovementAfterFallOn zeroes
 * the landing's deltaMovement, so the step onto the bed starts from {@code -0.0784}; BedBlock#bounceUp turns that into
 * {@code 0.0784 * 0.66F}, and the following tick's fall of {@code (0.051744 - 0.08) * 0.98F} clips on the bed's top.
 */
class JavaLandingBedStepTest {
    private static final double GRAVITY = 0.08D;
    private static final double AIR_DRAG = 0.98F;
    private static final double AIR_FRICTION = 0.91F;
    private static final double GROUND_FRICTION = 0.6F * 0.91F;
    private static final double BED_BOUNCE = 0.66F;
    private static final double BED_STEP = 0.5625D;
    private static final double FALL = -0.4D;
    private static final double DROP = 0.3D;
    private static final double SLIDE = 0.3D;

    @Test
    void theStepOntoABedBouncesTheLandingsZeroedVelocity() throws Exception {
        try (var f = landing()) {
            Vec3 at = land(f);
            double vx = SLIDE * AIR_FRICTION;
            at = at.add(vx, BED_STEP, 0.0D);
            PredictionResult step = f.tick(at, true);
            assertTrue(exact(step), "step: " + describe(step));

            for (int tick = 0; tick < 4; tick++) {
                vx *= GROUND_FRICTION;
                if (Math.abs(vx) < 0.003D) vx = 0.0D;
                at = at.add(vx, 0.0D, 0.0D);
                PredictionResult onBed = f.tick(at, true);
                assertTrue(exact(onBed), "tick " + tick + " on the bed: " + describe(onBed));
            }
        }
    }

    /** A client that rises off the bed as if the landing had kept its clipped fall into the bounce. */
    @Test
    void risingOffABedFromTheLandingsClippedFallFlags() throws Exception {
        try (var f = landing()) {
            Vec3 at = land(f);
            double vx = SLIDE * AIR_FRICTION;
            at = at.add(vx, BED_STEP, 0.0D);
            assertTrue(exact(f.tick(at, true)), "step");

            double clippedCarry = (-DROP - GRAVITY) * AIR_DRAG;
            double rise = (-clippedCarry * BED_BOUNCE - GRAVITY) * AIR_DRAG;
            vx *= GROUND_FRICTION;
            assertRejected(f.tick(at.add(vx, rise, 0.0D), false), "VerticalOffset");
        }
    }

    private static JavaTickFixture landing() throws Exception {
        var f = new JavaTickFixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11);
        f.player.compensatedWorld.updateBlock(
                new BlockPos(1, FLOOR_Y, 0),
                DataTables.defaults().registry().block("minecraft:blue_bed").defaultState());
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.place(new Vec3(0.2D, FLOOR_Y + DROP, 0.5D), new Vec3(SLIDE, FALL, 0.0D));
        f.player.onGround = false;
        f.player.lastOnGround = false;
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.FALSE);
        f.player.xRot = 0.0F;
        f.player.yRot = 0.0F;
        return f;
    }

    /** The fall of {@link #FALL} clipped by the stone floor after {@link #DROP}. */
    private static Vec3 land(JavaTickFixture f) {
        Vec3 at = new Vec3(0.2D + SLIDE, FLOOR_Y, 0.5D);
        PredictionResult landed = f.tick(at, true);
        assertTrue(exact(landed), "landing: " + describe(landed));
        return at;
    }
}
