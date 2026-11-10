package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static ac.cult.cultac.network.JavaTickFixture.setField;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A real 1.21.3 client teleported over a platform's east edge while holding forward and shift. Through 1.21.4
 * Entity.fallDistance is a float, and Player#isAboveGround passes canFallAtLeast the float {@code maxUpStep -
 * fallDistance}: after the first falling tick 0.6F - 0.0784F rounds 1.49e-8 below the exact difference. The moves are
 * the client's: the platform's top is FLOOR_Y + 1, its east face at x = 1, and the box's west face 1e-4 inside it.
 */
class JavaSneakEdgeFloatFallTest {
    private static final ServerboundPlayerInput SNEAK =
            new ServerboundPlayerInput(true, false, false, false, false, true, false);
    // The deltaMovement the tick that followed the teleport left: the backed-off input and the fall's first gravity.
    private static final Vec3 START_VELOCITY = new Vec3(0.016052403717917444, -0.0784000015258789, 0.0);
    private static final double X = 1.2999;

    /**
     * The platform's top lies inside the float probe's reach on the falling tick but not the exact difference's, so
     * Player#isAboveGround is false and Player#maybeBackOffFromEdge leaves the delta whole: the box leaves the edge.
     */
    @Test
    void theFloatDifferenceLetsTheBoxLeaveTheEdge() throws Exception {
        try (var f = fixture()) {
            f.place(new Vec3(X, 65.60001001698707, 0.5), START_VELOCITY);
            f.input(SNEAK);
            assertExact(f, new Vec3(X, 65.52161001546119, 0.5));
            assertExact(f, new Vec3(1.3257384888368182, 65.36637801094459, 0.5));
        }
    }

    /** 2e-8 lower the platform lies inside both probes, and the edge backs the delta off on the falling tick too. */
    @Test
    void lowerTheEdgeStillBacksOff() throws Exception {
        try (var f = fixture()) {
            Vec3 position = new Vec3(X, 65.60000999698707, 0.5);
            f.place(position, START_VELOCITY);
            f.input(SNEAK);
            double vy = START_VELOCITY.y;
            for (int tick = 0; tick < 2; tick++) {
                position = position.add(0.0, vy, 0.0);
                vy = (vy - 0.08D) * (double) 0.98F;
                assertExact(f, position);
            }
        }
    }

    private static JavaTickFixture fixture() throws Exception {
        var f = new JavaTickFixture(ProtocolVersion.V1_21_3, ClientVersion.V_1_21_2);
        for (int x = -3; x <= 0; x++) {
            for (int z = -3; z <= 3; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, z), BlockIds.STONE.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
        // LocalPlayer's yaw -90 walks toward +X.
        f.player.xRot = -90.0F;
        f.player.yRot = 0.0F;
        // The client held shift for 40 ticks before the teleport, so LocalPlayer#aiStep already crouches it.
        setField(SimulationProcessor.class, f.player.checkManager.getSimulationProcessor(), "lastSneaking", true);
        return f;
    }

    private static void assertExact(JavaTickFixture f, Vec3 position) {
        PredictionResult result = f.tick(position, false);
        assertTrue(exact(result), describe(result));
    }
}
