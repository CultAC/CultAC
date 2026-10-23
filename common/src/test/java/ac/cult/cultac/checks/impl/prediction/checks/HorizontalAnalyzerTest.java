package ac.cult.cultac.checks.impl.prediction.checks;

import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.Test;

import static org.junit.Assert.*;

public class HorizontalAnalyzerTest {
    private static final ClientVersion[] VERSIONS = {ClientVersion.V_1_8, ClientVersion.V_1_21_11, ClientVersion.V_26_3};
    private static final Vec3[] MOVEMENTS = {new Vec3(0.1, 0, 0), new Vec3(0, 0, -0.1), new Vec3(0.07, 0, -0.03)};
    private static final float[] YAWS = {0, 37.5f, -135, 720.25f, 1.0e6f, 1.17e7f, 1.18e7f, 1.2e7f, -1.18e7f, -1.2e7f,
            1.9e8f, -1.9e8f, 5.0e16f, -5.0e16f, 5.1e16f, -5.1e16f, 1.0e30f, -1.0e30f, Float.MAX_VALUE, -Float.MAX_VALUE};

    @Test public void noYawExplainsMovementWithLessInput() {
        for (ClientVersion version : VERSIONS) {
            for (float yaw : YAWS) {
                for (Vec3 movement : MOVEMENTS) {
                    double input = HorizontalAnalyzer.getBestTheoreticalPlayerInput(movement, yaw, version).length();
                    assertTrue(version + " yaw " + yaw + " input " + input, Double.isFinite(input));
                    assertTrue(version + " yaw " + yaw + " input " + input, input >= movement.length() * (1 - 1.0E-6));
                }
            }
        }
    }

    @Test public void saturatedYawRequiresImpossibleInput() {
        Vec3 movement = new Vec3(0.1, 0, 0.05);
        for (float yaw : new float[]{5.0e16f, -5.0e16f}) {
            assertTrue(HorizontalAnalyzer.getBestTheoreticalPlayerInput(movement, yaw, ClientVersion.V_1_8).length() > 10);
        }
        // Every trig table collapses to zero here, so no input can move the player at all
        assertTrue(HorizontalAnalyzer.getBestTheoreticalPlayerInput(movement, -1.0e30f, ClientVersion.V_26_3).length() > 1.0E30);
        assertEquals(Vec3.ZERO, HorizontalAnalyzer.getBestTheoreticalPlayerInput(Vec3.ZERO, -1.0e30f, ClientVersion.V_26_3));
    }

    @Test public void unsaturatedTableExplainsMovementWhenOnlyOneSaturates() {
        Vec3 movement = new Vec3(0.1, 0, 0.05);
        // 1.21.11+ index with a long, so these keep a real rotation while the legacy and Optifine int indices saturate
        assertEquals(movement.length(), HorizontalAnalyzer.getBestTheoreticalPlayerInput(movement, 1.0e8f, ClientVersion.V_1_21_11).length(), 1.0E-6);
        assertEquals(movement.length(), HorizontalAnalyzer.getBestTheoreticalPlayerInput(movement, 1.0e9f, ClientVersion.V_1_21_11).length(), 1.0E-6);
        // Legacy vanilla saturates first, Optifine still rotates
        assertEquals(movement.length(), HorizontalAnalyzer.getBestTheoreticalPlayerInput(movement, 1.0e8f, ClientVersion.V_1_8).length(), 1.0E-6);
    }

    @Test public void saneYawInputMatchesRotation() {
        // Pure forward input facing south-west at yaw 45 moves towards -X/+Z
        Vec3 input = HorizontalAnalyzer.getBestTheoreticalPlayerInput(new Vec3(-0.1, 0, 0.1), 45, ClientVersion.V_1_8);
        assertEquals(0, input.x, 1.0E-4);
        assertEquals(Math.sqrt(0.02), input.z, 1.0E-4);
    }
}
