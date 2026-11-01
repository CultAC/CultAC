package ac.cult.cultac.utils.nmsutil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BoatRiderYawTest {
    @Test
    void clampRotationMatchesMthFloatOperations() {
        Random random = new Random(1);
        for (int i = 0; i < 100_000; i++) {
            float yaw = (float) ((random.nextDouble() - 0.5) * 4000.0);
            float boatYaw = (float) ((random.nextDouble() - 0.5) * 4000.0);
            assertEquals(
                    Float.floatToIntBits(vanillaClamp(yaw, boatYaw)),
                    Float.floatToIntBits(BoatRiderYaw.clampRotation(yaw, boatYaw)));
        }
    }

    @Test
    void yawsPastTheClampFromABoatAtZeroAreNotHeld() {
        for (float yaw : new float[] {120.0F, 150.0F, 179.0F, -150.0F, 105.1F, -105.1F}) {
            assertFalse(BoatRiderYaw.canHold(yaw, 0.0F), Float.toString(yaw));
        }
    }

    @Test
    void everyYawTheClampReturnsIsHeld() {
        Random random = new Random(2);
        for (double magnitude : new double[] {360.0, 2000.0, 1.0e5, 1.0e7}) {
            for (int i = 0; i < 50_000; i++) {
                float boatYaw = (float) ((random.nextDouble() * 2.0 - 1.0) * magnitude);
                double side = random.nextBoolean() ? 1.0 : -1.0;
                float yaw = (float) (boatYaw + side * (100.0 + random.nextDouble() * 80.0) + 360.0 * random.nextInt(3));
                float held = vanillaClamp(yaw, boatYaw);
                assertTrue(BoatRiderYaw.canHold(held, boatYaw), held + " from " + yaw + " at " + boatYaw);
            }
        }
    }

    // Every float within 260 degrees of the boat clamped, against every yaw near the clamp's edges.
    @Test
    void heldYawsAreExactlyTheClampOutputs() {
        for (float boatYaw : new float[] {400.3F, 12345.678F, 130950.37F}) {
            Set<Float> outputs = new HashSet<>();
            for (float yaw = boatYaw - 260.0F; yaw <= boatYaw + 260.0F; yaw = Math.nextUp(yaw)) {
                float held = vanillaClamp(yaw, boatYaw);
                if (!withinComparison(held, boatYaw)) outputs.add(held);
            }
            Set<Float> candidates = new HashSet<>(outputs);
            for (float edge : new float[] {boatYaw + 105.0F, boatYaw - 105.0F}) {
                float below = edge;
                float above = edge;
                for (int step = 0; step <= 300; step++) {
                    candidates.add(below);
                    candidates.add(above);
                    below = Math.nextDown(below);
                    above = Math.nextUp(above);
                }
            }
            int checked = 0;
            for (float yaw : candidates) {
                if (withinComparison(yaw, boatYaw)) continue;
                checked++;
                assertEquals(outputs.contains(yaw), BoatRiderYaw.canHold(yaw, boatYaw), yaw + " at " + boatYaw);
            }
            assertTrue(checked > 300, "too few yaws past the comparison at " + boatYaw);
        }
    }

    // AbstractBoat#clampRotation with Mth#wrapDegrees and Mth#clamp written out.
    private static float vanillaClamp(float yaw, float boatYaw) {
        float delta = (yaw - boatYaw) % 360.0F;
        if (delta >= 180.0F) delta -= 360.0F;
        if (delta < -180.0F) delta += 360.0F;
        float target = delta < -105.0F ? -105.0F : Math.min(delta, 105.0F);
        return yaw + target - delta;
    }

    private static boolean withinComparison(float yaw, float boatYaw) {
        float delta = (yaw - boatYaw) % 360.0F;
        if (delta >= 180.0F) delta -= 360.0F;
        if (delta < -180.0F) delta += 360.0F;
        return delta >= -105.0F && delta <= 105.0F;
    }
}
