package ac.grim.grimac.checks.impl.prediction.stage;

import ac.grim.grimac.checks.impl.prediction.PredVector;
import ac.grim.grimac.checks.impl.prediction.stage.uncertainty.ElytraTransform;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.math.TrigHandler;
import ac.grim.grimac.utils.nmsutil.ReachUtils;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class VelocityTransformerTest {
    private static Vec3 threshold(ClientVersion version, boolean player, Vec3 velocity) {
        return VelocityTransformer.applyMovementThreshold(
                List.of(new PredVector(velocity)), version, player).getFirst();
    }

    @Test
    public void olderPlayersZeroSmallAxesIndependently() {
        for (ClientVersion version : List.of(ClientVersion.V_1_9, ClientVersion.V_1_21_2, ClientVersion.V_1_21_4)) {
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
    public void legacyCutoffAndStrictBoundaryArePreserved() {
        assertEquals(Vec3.ZERO, threshold(ClientVersion.V_1_8, true, new Vec3(0.004, -0.004, 0.004)));
        Vec3 boundary = new Vec3(0.003, -0.003, 0.003);
        assertEquals(boundary, threshold(ClientVersion.V_1_21_4, true, boundary));
        Vec3 legacyBoundary = new Vec3(0.005, -0.005, 0.005);
        assertEquals(legacyBoundary, threshold(ClientVersion.V_1_8, true, legacyBoundary));
    }

    @Test
    public void loggedOneTwentyOneTwoGlideEntryMatchesClientMovement() {
        // flag-2-2026-09-05T00-00-31.149048116Z: first fall-flying tick.
        assertGlideEntry(new Vec3(0.0018736547541902846, -0.3015347236627735, 0.08470307270495961),
                new Vec3(-0.0025101238160800676, -0.32102270065252014, 0.10136327814836932),
                725.53986F, 40.99986F);
    }

    @Test
    public void loggedOneTwentyOneTwoGlideEntryWithSmallZMatchesClientMovement() {
        // flag-1-2026-09-05T00-06-50.381895492Z: same cutoff on the Z axis.
        assertGlideEntry(new Vec3(0.2165662757952252, -0.22768848754498106, -0.0018852491752010354),
                new Vec3(0.23281205629342594, -0.23384168529646843, -0.003670572371675007),
                264.73898F, 26.299835F);
    }

    private static void assertGlideEntry(Vec3 carried, Vec3 expected, float yaw, float pitch) {
        GrimPlayer player = mock(GrimPlayer.class);
        when(player.getClientVersion()).thenReturn(ClientVersion.V_1_21_2);
        player.trigHandler = new TrigHandler(player);
        Vec3 predicted = ElytraTransform.getElytraMovement(player,
                threshold(ClientVersion.V_1_21_2, true, carried),
                ReachUtils.getLook(player, yaw, pitch), pitch, 0.08);

        assertTrue("Logged glide offset: " + predicted.distanceTo(expected), predicted.distanceTo(expected) < 1.0E-12);
    }
}
