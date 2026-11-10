package ac.cult.cultac.checks.impl.prediction.checks;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaBlockEntityPass;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPassAfterTravel;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPassState;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ClientPistonDeltas;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PistonInputAngleTest {
    /** A live airborne forward travel at yaw 32.49, followed by a full sideways piston shove. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void theInputAngleUsesTravelBeforeThePiston(boolean illegalAngle) {
        Vec3 inertia = new Vec3(-0.02873925409756608, 0.33319999363422365, 0.04513493385677914);
        Vec3 shove = new Vec3(0.51, 0, 0);
        Vec3 net = new Vec3(0.4707335482142412, 0.33319999363422426, 0.06166787412522012);
        if (illegalAngle) {
            double angle = Math.toRadians(32.49 + 22.5);
            net = inertia.add(-Math.sin(angle) * 0.0196, 0, Math.cos(angle) * 0.0196)
                    .add(shove);
        }
        var result = mock(PredictionResult.class);
        var valid = mock(ValidMovements.class);
        var support = new MainSupportingBlockData(null, false);
        var travel = new JavaPassState(Vec3.ZERO, inertia, false, support, null, 0, ClientPistonDeltas.NONE);
        var after = new JavaPassState(shove, inertia, false, support, null, 0, ClientPistonDeltas.NONE);
        var pass = new JavaPassAfterTravel(
                travel,
                Pose.STANDING,
                new JavaBlockEntityPass.Result(after, true, false, false, false),
                false,
                net.subtract(shove).subtract(inertia),
                false,
                false);
        var trace = new MovementTrace(new PredVector(inertia), shove, shove, inertia, inertia, pass);
        when(result.getValidMovements()).thenReturn(valid);
        when(valid.getCollisionIgnoredMaxStartingVelExtents()).thenReturn(new SimpleCollisionBox(inertia, inertia));
        if (!illegalAngle) {
            var raw = HorizontalAnalyzer.inputAngleRange(result, net, 32.49);
            assertTrue(
                    HorizontalAnalyzer.minDistanceToValidMovementAngle(raw.getMinAngle(), raw.getMaxAngle()) > 7,
                    "the packet's net direction reproduces the live false flag without the pass");
        }
        when(valid.getClosestTrace()).thenReturn(trace);
        var range = HorizontalAnalyzer.inputAngleRange(result, net, 32.49);
        double offset = HorizontalAnalyzer.minDistanceToValidMovementAngle(range.getMinAngle(), range.getMaxAngle());
        if (illegalAngle) assertTrue(offset > 7, "the same shove must not admit an invalid keyboard direction");
        else
            assertTrue(
                    offset <= 360.0 / 65536, // One vanilla sine-table bin; the live yaw was 32.49F.
                    "the piston is position-only movement, not the player's input direction: " + offset);
    }
}
