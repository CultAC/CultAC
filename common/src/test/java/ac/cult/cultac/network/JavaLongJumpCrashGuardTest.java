package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.crash.CrashF;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Jump boost 250: LivingEntity#getJumpPower adds 0.1F * (amplifier + 1) to the jump strength, so a legitimate jump
 * leaves the ground at about 25.5 blocks per tick. A move over 20 blocks is accepted up to the engine's own travel.
 */
class JavaLongJumpCrashGuardTest {
    private static final ServerboundPlayerInput JUMP =
            new ServerboundPlayerInput(false, false, false, false, true, false, false);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final int AMPLIFIER = 250;
    private static final double JUMP_POWER = 0.42F + 0.1F * (AMPLIFIER + 1.0F);

    @Test
    void aJumpBoostedJumpAndItsFirstFallingTickArePredicted() throws Exception {
        try (var f = grounded()) {
            CrashF crash = f.player.checkManager.getListener(CrashF.class);
            f.input(JUMP);
            Vec3 apex = START.add(0.0, JUMP_POWER, 0.0);
            PredictionResult jump = f.tick(apex, false);
            assertTrue(exact(jump), describe(jump));

            f.input(NONE);
            double falling = (JUMP_POWER - 0.08) * 0.98F;
            PredictionResult next = f.tick(apex.add(0.0, falling, 0.0), false);
            assertTrue(exact(next), describe(next));
            assertEquals(0.0, crash.violations);
        }
    }

    @Test
    void aMoveLongerThanEveryTravelOfTheTickIsRejected() throws Exception {
        try (var f = grounded()) {
            CrashF crash = f.player.checkManager.getListener(CrashF.class);
            PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
            f.input(JUMP);
            f.move(START.add(0.0, JUMP_POWER + 1.0, 0.0), false);
            f.tickEnd();
            assertEquals(1.0, crash.violations);
            assertSame(before, f.player.checkManager.getSimulationProcessor().getLastPrediction());
        }
    }

    private static JavaTickFixture grounded() throws Exception {
        var f = new JavaTickFixture();
        f.player.compensatedEntities.getSelf().addPotionEffect(MovementEffect.JUMP_BOOST, AMPLIFIER);
        f.place(START, new Vec3(0.0, -0.0784000015258789, 0.0));
        f.input(NONE);
        PredictionResult idle = f.tick(START, true);
        assertTrue(exact(idle), describe(idle));
        return f;
    }
}
