package ac.cult.cultac.checks.impl.prediction.checks;

import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
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

    @Test
    public void zeroSpeedAcceptsTheInputFreeMove() {
        // A sprint jump's decaying ground tick at movement_speed 0: the reported move is the input-free movement
        Vec3 target = new Vec3(0.06040103800674501, 0, 0.009362543478260932);
        Vec3 inputFree = new Vec3(0.0604010380067536, 0, 0.009362543478269269);
        Vec3 input = HorizontalAnalyzer.getBestTheoreticalPlayerInput(
                target.subtract(inputFree), -81.15678f, ClientVersion.V_26_3);
        assertEquals(
                0,
                HorizontalAnalyzer.getHorizontalInputFlagAmount(Math.abs(input.x), Math.abs(input.z), 0f, 1.3, false),
                0);
    }

    @Test
    public void diagonalInputPastTheSprintCircleFlags() {
        // Each axis is under 0.98 of the sprint speed, the diagonal is past the unit circle moveRelative allows
        assertTrue(HorizontalAnalyzer.getHorizontalInputFlagAmount(0.093, 0.093, 0.1f, 1.3, false) > 0);
        assertEquals(0, HorizontalAnalyzer.getHorizontalInputFlagAmount(0.09, 0.09, 0.1f, 1.3, false), 0);
    }

    @Test public void aForwardInputWithinAWideStartingRangeHasForwardImpulse() {
        for (ClientVersion version : new ClientVersion[]{ClientVersion.V_1_21_4, ClientVersion.V_26_3}) {
            SimpleCollisionBox wideOnX = new SimpleCollisionBox(-0.2, 0, 0, 0.2, 0, 0.001);
            assertEquals(0, HorizontalAnalyzer.distanceToForwardImpulse(wideOnX, farCorner(wideOnX), new Vec3(0, 0, 0.13), 0, version), 0);
            SimpleCollisionBox wideOnZ = new SimpleCollisionBox(0, 0, -0.2, 0.001, 0, 0.2);
            assertEquals(0, HorizontalAnalyzer.distanceToForwardImpulse(wideOnZ, farCorner(wideOnZ), new Vec3(-0.13, 0, 0), 90, version), 0);
        }
    }

    @Test public void aBackwardInputOutsideAWideStartingRangeIsItsDistanceToForwardImpulse() {
        for (ClientVersion version : new ClientVersion[]{ClientVersion.V_1_21_4, ClientVersion.V_26_3}) {
            SimpleCollisionBox wideOnX = new SimpleCollisionBox(-0.2, 0, 0, 0.2, 0, 0.001);
            assertEquals(0.13, HorizontalAnalyzer.distanceToForwardImpulse(wideOnX, farCorner(wideOnX), new Vec3(0, 0, -0.13), 0, version), 1.0E-6);
            SimpleCollisionBox narrow = new SimpleCollisionBox(-0.01, 0, -0.01, 0.01, 0, 0.01);
            assertEquals(0.06 / Math.sqrt(2), HorizontalAnalyzer.distanceToForwardImpulse(narrow, farCorner(narrow), new Vec3(0.09, 0, 0.01), 0, version), 1.0E-6);
        }
    }

    private static Vec3 farCorner(SimpleCollisionBox box) {
        return new Vec3(box.maxX, 0, box.maxZ);
    }

    @Test public void saneYawInputMatchesRotation() {
        // Pure forward input facing south-west at yaw 45 moves towards -X/+Z
        Vec3 input = HorizontalAnalyzer.getBestTheoreticalPlayerInput(new Vec3(-0.1, 0, 0.1), 45, ClientVersion.V_1_8);
        assertEquals(0, input.x, 1.0E-4);
        assertEquals(Math.sqrt(0.02), input.z, 1.0E-4);
    }
}
