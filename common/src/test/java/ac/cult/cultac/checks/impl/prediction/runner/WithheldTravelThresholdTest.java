package ac.cult.cultac.checks.impl.prediction.runner;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/** LocalPlayer#sendPosition withholds a position only while it stays within the movement threshold of xLast. */
class WithheldTravelThresholdTest {
    private static final double THRESHOLD = 2.0E-4D;
    private static final Vec3 POSITION = new Vec3(0.0D, 64.0D, 0.0D);
    private static final Vec3 SENT = new Vec3(1.0E-4D, 64.0D, 0.0D);

    @Test
    void keepsTheBestTravelWhenItEndsWithinTheThreshold() {
        PredictionResult travelled = result(2.5E-4D, 0.0D, List.of(result(1.0E-4D, 0.0D, List.of())));

        assertSame(travelled, SimulationProcessor.travelWithinMovementThreshold(travelled, POSITION, SENT, THRESHOLD));
    }

    @Test
    void picksTheNearestCleanRealityWithinTheThreshold() {
        PredictionResult flagged = result(1.0E-4D, 1.0D, List.of());
        PredictionResult far = result(2.5E-4D, 0.0D, List.of());
        PredictionResult near = result(0.5E-4D, 0.0D, List.of());
        PredictionResult nearer = result(1.2E-4D, 0.0D, List.of());
        PredictionResult travelled = result(1.0E-3D, 0.0D, List.of(flagged, far, near, nearer));

        assertSame(nearer, SimulationProcessor.travelWithinMovementThreshold(travelled, POSITION, SENT, THRESHOLD));
    }

    @Test
    void rejectsATravelThatWouldHaveSentItsPosition() {
        PredictionResult travelled = result(1.0E-3D, 0.0D, List.of(result(-2.0E-4D, 0.0D, List.of())));

        assertNull(SimulationProcessor.travelWithinMovementThreshold(travelled, POSITION, SENT, THRESHOLD));
    }

    private static PredictionResult result(double travelX, double severity, List<PredictionResult> realities) {
        PredictionResult result = mock(PredictionResult.class);
        when(result.getAcceptedClosestToTarget()).thenReturn(new Vec3(travelX, 0.0D, 0.0D));
        when(result.getFlagSeverity()).thenReturn(severity);
        when(result.getRealities()).thenReturn(realities);
        return result;
    }
}
