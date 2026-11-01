package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A 1.21.4 client lands on a slime floor under a slime ceiling two blocks up while holding shift, then releases it.
 * SlimeBlock#stepOn, from LivingEntity#aiStep after travel, leaves the landing's velocity whole because
 * Entity#isSteppingCarefully holds that tick's shift key; the next tick LocalPlayer#aiStep still crouches on the
 * previous tick's shift key and slows the input to sneaking_speed. The moves are a real client's, translated onto the
 * fixture.
 */
class JavaSlimeSneakLandingTest {
    private static final ServerboundPlayerInput BACK_RIGHT_SHIFT =
            new ServerboundPlayerInput(false, true, false, true, false, true, true);
    private static final ServerboundPlayerInput BACK_RIGHT =
            new ServerboundPlayerInput(false, true, false, true, false, false, true);
    private static final Vec3 LANDING_FROM = recorded(269.6344299573542, 62.12160004615784, 150.46952950682223);
    private static final Vec3 LANDING_TO = recorded(269.6465377714902, 62.0, 150.38082294404805);

    @Test
    void aClientReleasingShiftOnSlimeMovesAtSneakingSpeed() throws Exception {
        try (var f = landed()) {
            PredictionResult released = f.tick(recorded(269.65014761664725, 62.0, 150.28420051214866), true);
            assertTrue(exact(released), describe(released));
        }
    }

    @Test
    void aClientReleasingShiftOnSlimeAtFullSpeedFlags() throws Exception {
        try (var f = landed()) {
            // The landing's velocity after air friction plus the unslowed back-right input, which Entity#getInputVector
            // normalizes.
            Vec3 velocity = LANDING_TO.subtract(LANDING_FROM).multiply(0.91F, 0, 0.91F);
            float sin = (float) Math.sin(f.player.xRot * (Math.PI / 180.0));
            float cos = (float) Math.cos(f.player.xRot * (Math.PI / 180.0));
            double input = Math.sqrt(0.5) * 0.1F * (0.21600002F / (0.8F * 0.8F * 0.8F));
            Vec3 move = velocity.add(-input * cos + input * sin, 0, -input * cos - input * sin);
            assertRejected(f.tick(LANDING_TO.add(move), true), "NoSneakSlow");
        }
    }

    /** The landing tick, after which the client turns and releases shift. */
    private static JavaTickFixture landed() throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_4);
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                f.player.compensatedWorld.updateBlock(
                        new BlockPos(x, FLOOR_Y - 1, z), BlockIds.SLIME_BLOCK.defaultState());
                f.player.compensatedWorld.updateBlock(
                        new BlockPos(x, FLOOR_Y + 2, z), BlockIds.SLIME_BLOCK.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);

        // The fall from the ceiling: the last airborne move through LivingEntity#travel's air friction and gravity.
        Vec3 fall = LANDING_FROM.subtract(recorded(269.6156299346299, 62.200000047683716, 150.5597078138167));
        f.place(LANDING_FROM, new Vec3(fall.x * 0.91F, (fall.y - 0.08) * 0.98F, fall.z * 0.91F));
        f.player.xRot = 1088.0381F;
        f.player.yRot = 29.925932F;
        f.input(BACK_RIGHT_SHIFT);
        PredictionResult landing = f.tick(LANDING_TO, true);
        assertTrue(exact(landing), "landing: " + describe(landing));

        f.player.xRot = 1100.0195F;
        f.player.yRot = 30.09468F;
        f.input(BACK_RIGHT);
        return f;
    }

    /** A recorded position moved onto the fixture: the slime top 62.0 becomes FLOOR_Y. */
    private static Vec3 recorded(double x, double y, double z) {
        return new Vec3(x - 269.0, y - 62.0 + FLOOR_Y, z - 150.0);
    }
}
