package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import static org.junit.Assert.assertEquals;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.Test;

public class FluidPushTest {
    private static final double WATER_ENTRY_RADIUS = 0.028D;
    private static final double EPSILON = 1.0E-12D;

    @Test
    public void risingMovementDoesNotReceiveVerticalFluidPush() {
        PredVector predicted = new PredVector(new Vec3(0.0D, 0.1D, 0.0D));
        Vec3 target = new Vec3(0.0D, 0.2D, 0.0D);

        assertEquals(0.0D, FluidPush.verticalRadius(WATER_ENTRY_RADIUS, predicted, target), EPSILON);
    }

    @Test
    public void fallingMovementReceivesVerticalFluidPush() {
        PredVector predicted = new PredVector(new Vec3(0.0D, 0.1D, 0.0D));
        Vec3 target = new Vec3(0.0D, 0.05D, 0.0D);

        assertEquals(WATER_ENTRY_RADIUS, FluidPush.verticalRadius(WATER_ENTRY_RADIUS, predicted, target), EPSILON);
    }
}
