package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.utils.data.CollideAxisData;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JavaFallDistanceLandingTest {
    @Test
    public void stuckLandingScalesTheClipBackIntoMovementSpace() {
        // Cobweb: Entity#makeStuckInBlock(..., new Vec3(0.25, 0.05, 0.25)). The client-visible
        // landing delta is -0.05, while CollisionModifier stores the clip divided by 0.05 (-1.0).
        double stuckY = 0.05D;
        CollideAxisData.CollideResult stored = new CollideAxisData.CollideResult(true, -0.05D / stuckY);

        assertEquals(-0.05D, stored.inMovementSpace(stuckY), 1.0E-15D);
        assertTrue(JavaFallDistance.landsOnClip(stored, -0.05D, stuckY));
        // The unscaled comparison (old commit() code) can never see this landing.
        assertFalse(Math.abs(-0.05D - stored.getResult()) <= 1.0E-7D);
        // A landing resets the fall distance instead of accumulating it.
        assertEquals(0.0D, JavaFallDistance.afterMove(5.0D, -0.05D, false, true), 1.0E-15D);
        assertEquals(5.05D, JavaFallDistance.afterMove(5.0D, -0.05D, false, false), 1.0E-6D);
    }

    @Test
    public void powderSnowLandingAlsoScales() {
        double stuckY = 0.9D;
        CollideAxisData.CollideResult stored = new CollideAxisData.CollideResult(true, -0.1D / stuckY);
        assertEquals(-0.1D, stored.inMovementSpace(stuckY), 1.0E-15D);
        assertTrue(JavaFallDistance.landsOnClip(stored, -0.1D, stuckY));
    }

    @Test
    public void unstuckLandingIsUnchanged() {
        CollideAxisData.CollideResult stored = new CollideAxisData.CollideResult(true, -0.05D);
        assertTrue(JavaFallDistance.landsOnClip(stored, -0.05D, 1.0D));
        assertFalse(JavaFallDistance.landsOnClip(stored, -0.06D, 1.0D));
        assertFalse(JavaFallDistance.landsOnClip(null, -0.05D, 1.0D));
        assertFalse(JavaFallDistance.landsOnClip(
                new CollideAxisData.CollideResult(false, -0.05D), -0.05D, 1.0D));
    }
}
