package ac.cult.cultac.checks.impl.prediction.stage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ElytraTransform;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.TrigHandler;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import java.util.List;
import org.junit.Test;

public class VelocityTransformerTest {
    private static Vec3 threshold(ClientVersion version, boolean player, Vec3 velocity) {
        return VelocityTransformer.applyMovementThreshold(List.of(new PredVector(velocity)), version, player)
                .getFirst();
    }

    @Test
    public void olderPlayersZeroSmallAxesIndependently() {
        for (ClientVersion version : List.of(ClientVersion.V_1_21_2, ClientVersion.V_1_21_4)) {
            assertEquals(new Vec3(0, 0, 0.08), threshold(version, true, new Vec3(0.002, -0.002, 0.08)));
            assertEquals(new Vec3(0.08, 0, 0), threshold(version, true, new Vec3(0.08, 0.002, -0.002)));
            assertEquals(new Vec3(0, 0, 0), threshold(version, true, new Vec3(0.0025, 0, -0.0025)));
        }
    }

    @Test
    public void modernPlayersUseHorizontalLengthButVehiclesKeepPerAxisCutoffs() {
        Vec3 velocity = new Vec3(0.0025, 0.002, -0.0025);
        assertEquals(new Vec3(0.0025, 0, -0.0025), threshold(ClientVersion.V_1_21_5, true, velocity));
        assertEquals(Vec3.ZERO, threshold(ClientVersion.V_1_21_5, true, new Vec3(0.001, 0.002, -0.001)));
        assertEquals(Vec3.ZERO, threshold(ClientVersion.V_1_21_5, false, velocity));
    }

    @Test
    public void legacyHorseDiagonalKeepsTheRawImpulsesTimesNinetyEightHundredths() {
        // 1.21.2-1.21.4: xxa/zza = +-1 * 0.98F, AbstractHorse#getRiddenInput halves xxa.
        for (ClientVersion version : List.of(ClientVersion.V_1_21_2, ClientVersion.V_1_21_4)) {
            assertEquals(
                    new Vec3(0.49F, 0, 0.98F),
                    VelocityTransformer.getExactHorseFamilyRiddenInput(version, new Vec3(1, 0, 1)));
            assertEquals(
                    new Vec3(-0.49F, 0, -0.98F * 0.25F),
                    VelocityTransformer.getExactHorseFamilyRiddenInput(version, new Vec3(-1, 0, -1)));
        }
        // 1.21.5+ LocalPlayer#modifyInputSpeedForSquareMovement keeps the diagonal on the unit square.
        Vec3 modern = VelocityTransformer.getExactHorseFamilyRiddenInput(ClientVersion.V_1_21_5, new Vec3(1, 0, 1));
        assertEquals(0.70710677F * 0.5F, modern.x, 1.0E-7);
        assertEquals(0.70710677F, modern.z, 1.0E-7);
    }

    @Test
    public void strictBoundaryIsPreserved() {
        Vec3 boundary = new Vec3(0.003, -0.003, 0.003);
        assertEquals(boundary, threshold(ClientVersion.V_1_21_4, true, boundary));
    }

    @Test
    public void loggedOneTwentyOneTwoGlideEntryMatchesClientMovement() {
        // flag-2-2026-09-05T00-00-31.149048116Z: first fall-flying tick.
        assertGlideEntry(
                new Vec3(0.0018736547541902846, -0.3015347236627735, 0.08470307270495961),
                new Vec3(-0.0025101238160800676, -0.32102270065252014, 0.10136327814836932),
                725.53986F,
                40.99986F);
    }

    @Test
    public void loggedOneTwentyOneTwoGlideEntryWithSmallZMatchesClientMovement() {
        // flag-1-2026-09-05T00-06-50.381895492Z: same cutoff on the Z axis.
        assertGlideEntry(
                new Vec3(0.2165662757952252, -0.22768848754498106, -0.0018852491752010354),
                new Vec3(0.23281205629342594, -0.23384168529646843, -0.003670572371675007),
                264.73898F,
                26.299835F);
    }

    @Test
    public void crouchingInputMatchesLocalPlayerModifyInput() {
        for (float slow : new float[] {1.0F, 0.3F, 0.65F, 1.0F / 3.0F}) {
            for (int strafe = -1; strafe <= 1; strafe++) {
                for (int forward = -1; forward <= 1; forward++) {
                    Vec3 modified = VelocityTransformer.getExactControllerModifiedInputStatic(
                            new Vec3(strafe, 0, forward), slow);
                    float[] expected = vanillaModifyInput(strafe, forward, slow);
                    assertEquals(slow + " " + strafe + " " + forward, expected[0], (float) modified.x, 0.0F);
                    assertEquals(slow + " " + strafe + " " + forward, expected[1], (float) modified.z, 0.0F);
                }
            }
        }
    }

    /** KeyboardInput#tick's Vec2#normalized, then LocalPlayer#modifyInput and #modifyInputSpeedForSquareMovement. */
    private static float[] vanillaModifyInput(float x, float y, float slow) {
        float length = (float) Math.sqrt(x * x + y * y);
        if (length < 1.0E-4F) return new float[] {0.0F, 0.0F};
        x = x / length * 0.98F * slow;
        y = y / length * 0.98F * slow;
        float scaled = (float) Math.sqrt(x * x + y * y);
        float inverse = 1.0F / scaled;
        float directionX = x * inverse;
        float directionY = y * inverse;
        float absX = Math.abs(directionX);
        float absY = Math.abs(directionY);
        float tan = absY > absX ? absX / absY : absY / absX;
        float modifiedLength = Math.min(scaled * (float) Math.sqrt(1.0F + tan * tan), 1.0F);
        return new float[] {directionX * modifiedLength, directionY * modifiedLength};
    }

    private static void assertGlideEntry(Vec3 carried, Vec3 expected, float yaw, float pitch) {
        CultPlayer player = mock(CultPlayer.class);
        when(player.getClientVersion()).thenReturn(ClientVersion.V_1_21_2);
        player.trigHandler = new TrigHandler(player);
        Vec3 predicted = ElytraTransform.getElytraMovement(
                player,
                threshold(ClientVersion.V_1_21_2, true, carried),
                ReachUtils.getLook(player, yaw, pitch),
                pitch,
                0.08);

        assertTrue("Logged glide offset: " + predicted.distanceTo(expected), predicted.distanceTo(expected) < 1.0E-12);
    }
}
