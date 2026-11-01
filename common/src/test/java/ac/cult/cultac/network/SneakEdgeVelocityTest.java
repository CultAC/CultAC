package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.stage.VelocityTransformer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

class SneakEdgeVelocityTest {
    private static final ServerboundPlayerInput SNEAK =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);
    private static final ServerboundPlayerInput SNEAK_LEFT =
            new ServerboundPlayerInput(false, false, true, false, false, true, false);
    private static final ServerboundPlayerInput LEFT =
            new ServerboundPlayerInput(false, false, true, false, false, false, false);
    private static final ServerboundPlayerInput SNEAK_FORWARD_LEFT =
            new ServerboundPlayerInput(true, false, true, false, false, true, false);
    private static final ServerboundPlayerInput FORWARD_LEFT =
            new ServerboundPlayerInput(true, false, true, false, false, false, false);
    // JavaTickFixture's floor ends at x = 9; the player's box overhangs it by 0.299.
    private static final double EDGE_X = 9.299;
    private static final double SNEAK_INPUT = (double) (0.98F * 0.3F) * (double) 0.1F;
    private static final double GROUND_FRICTION = 0.6F * 0.91F;
    private static final int PACKED_ICE =
            DataTables.defaults().registry().block("minecraft:packed_ice").defaultState();
    private static final float ICE = 0.98F;
    private static final float ICE_FRICTION = ICE * 0.91F;
    // LivingEntity#getFrictionInfluencedSpeed on packed ice.
    private static final double ICE_SPEED = 0.1F * (0.21600002F / (ICE * ICE * ICE));
    private static final double ICE_SNEAK_INPUT = (double) (0.98F * 0.3F) * ICE_SPEED;

    @Test
    void releasingShiftReleasesTheVelocityTheEdgeHid() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(EDGE_X, FLOOR_Y, 0.5), new Vec3(0, -0.0784000015258789, 0));
            f.input(SNEAK);
            assertExact(-1, f.tick(new Vec3(EDGE_X, FLOOR_Y, 0.5), true));

            f.input(SNEAK_LEFT);
            double velocity = 0.0;
            for (int tick = 0; tick < 6; tick++) {
                assertExact(tick, f.tick(new Vec3(EDGE_X, FLOOR_Y, 0.5), true));
                velocity = (velocity + SNEAK_INPUT) * GROUND_FRICTION;
            }

            f.input(LEFT);
            assertExact(6, f.tick(new Vec3(EDGE_X + velocity + SNEAK_INPUT, FLOOR_Y, 0.5), true));
        }
    }

    @Test
    void releasingShiftReleasesMomentumTheEdgeHid() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(EDGE_X, FLOOR_Y, 0.5), new Vec3(0.2, -0.0784000015258789, 0));
            f.input(SNEAK);
            assertExact(0, f.tick(new Vec3(EDGE_X, FLOOR_Y, 0.5), true));
            f.input(LEFT);
            assertExact(1, f.tick(new Vec3(EDGE_X + 0.2 * GROUND_FRICTION + SNEAK_INPUT, FLOOR_Y, 0.5), true));
        }
    }

    @Test
    void releasingShiftAfterTwoTicksReleasesMomentumTheEdgeHid() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(EDGE_X, FLOOR_Y, 0.5), new Vec3(0.2, -0.0784000015258789, 0));
            f.input(SNEAK);
            assertExact(0, f.tick(new Vec3(EDGE_X, FLOOR_Y, 0.5), true));
            f.input(SNEAK_LEFT);
            assertExact(1, f.tick(new Vec3(EDGE_X, FLOOR_Y, 0.5), true));
            double velocity = (0.2 * GROUND_FRICTION + SNEAK_INPUT) * GROUND_FRICTION;
            f.input(LEFT);
            assertExact(2, f.tick(new Vec3(EDGE_X + velocity + SNEAK_INPUT, FLOOR_Y, 0.5), true));
        }
    }

    @Test
    void releasingShiftOnIceReleasesTheVelocityTheEdgeHid() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedWorld.updateBlock(new BlockPos(8, FLOOR_Y - 1, 0), PACKED_ICE);
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            f.place(new Vec3(EDGE_X, FLOOR_Y, 0.5), new Vec3(0, -0.0784000015258789, 0));
            f.input(SNEAK);
            assertExact(-1, f.tick(new Vec3(EDGE_X, FLOOR_Y, 0.5), true));

            f.input(SNEAK_LEFT);
            double velocity = 0.0;
            for (int tick = 0; tick < 6; tick++) {
                assertExact(tick, f.tick(new Vec3(EDGE_X, FLOOR_Y, 0.5), true));
                velocity = (velocity + ICE_SNEAK_INPUT) * ICE_FRICTION;
            }

            f.input(LEFT);
            assertExact(6, f.tick(new Vec3(EDGE_X + velocity + ICE_SNEAK_INPUT, FLOOR_Y, 0.5), true));
        }
    }

    @Test
    void releasingShiftOnIceReleasesTheAxisTheEdgeHidWhileTheOtherMoved() throws Exception {
        try (var f = new JavaTickFixture()) {
            for (int x = -8; x <= 8; x++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y - 1, 8), PACKED_ICE);
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 input = VelocityTransformer.getExactControllerModifiedInputStatic(new Vec3(1, 0, 1), 0.3F)
                    .scale(ICE_SPEED);
            double x = 0.5;
            f.place(new Vec3(x, FLOOR_Y, EDGE_X), new Vec3(0, -0.0784000015258789, 0));
            f.input(SNEAK);
            assertExact(-1, f.tick(new Vec3(x, FLOOR_Y, EDGE_X), true));

            // The floor ends at z = 9: the edge hides z while x keeps moving.
            f.input(SNEAK_FORWARD_LEFT);
            double velocityX = 0.0;
            double velocityZ = 0.0;
            for (int tick = 0; tick < 8; tick++) {
                x += velocityX + input.x;
                assertExact(tick, f.tick(new Vec3(x, FLOOR_Y, EDGE_X), true));
                velocityX = (velocityX + input.x) * ICE_FRICTION;
                velocityZ = (velocityZ + input.z) * ICE_FRICTION;
            }

            f.input(FORWARD_LEFT);
            assertExact(8, f.tick(new Vec3(x + velocityX + input.x, FLOOR_Y, EDGE_X + velocityZ + input.z), true));
        }
    }

    private static void assertExact(int tick, PredictionResult result) {
        assertTrue(exact(result), "tick " + tick + " " + describe(result));
    }
}
