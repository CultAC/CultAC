package ac.cult.cultac.checks.impl.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaBlockEntityPass;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMoveCollision;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPassAfterTravel;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPassState;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.utils.data.ClientPistonDeltas;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientHorizontalCollisionPassTest {
    private static final Vec3 INPUT = new Vec3(-0.02548, 0, 0);
    private static final Vec3 TRAVEL = new Vec3(-0.02548, 0, 0.0189);
    // The travel runs into a moving piston face 0.01 away: X is clipped and the move turns sideways, a hard collision.
    private static final Vec3 CLIPPED = new Vec3(-0.01, 0, 0.0189);

    /**
     * Entity#move sets horizontalCollision and minorHorizontalCollision on every move. The piston's MoverType.PISTON
     * shove after the travel replaces the travel's flags, so LocalPlayer#shouldStopRunSprinting reads the shove's.
     */
    @Test
    void theLastPistonMoveDecidesTheSprintStoppingCollision() {
        assertEquals(DesyncStatus.TRUE, ClientHorizontalCollision.hard(result(null)));
        var free = new JavaBlockEntityPass.LastMove(new Vec3(0.5, 0, 0), new Vec3(0.5, 0, 0));
        assertEquals(DesyncStatus.FALSE, ClientHorizontalCollision.hard(result(free)));
        var blocked = new JavaBlockEntityPass.LastMove(new Vec3(-0.5, 0, 0), new Vec3(0, 0, 0));
        assertEquals(DesyncStatus.TRUE, ClientHorizontalCollision.hard(result(blocked)));
        // A hard pass move only counts after a hard travel.
        assertEquals(DesyncStatus.FALSE, ClientHorizontalCollision.hard(result(blocked, new Vec3(-0.02548, 0, 0))));
        // A shove clipped by less than Mth.equal's 1.0E-5F sets no horizontalCollision.
        var grazing = new JavaBlockEntityPass.LastMove(new Vec3(-0.5, 0, 0), new Vec3(-0.499995, 0, 0));
        assertEquals(DesyncStatus.FALSE, ClientHorizontalCollision.hard(result(grazing)));
    }

    /** Every candidate that explains the report must agree before the collision is hard for certain. */
    @Test
    void candidatesThatDisagreeLeaveTheCollisionUnknown() {
        var hard = result(null);
        var free = result(null, new Vec3(-0.02548, 0, 0));
        when(hard.getRealities()).thenReturn(List.of(hard, free));
        assertEquals(DesyncStatus.UNKNOWN, ClientHorizontalCollision.hardForEveryReality(hard));
        when(hard.getRealities()).thenReturn(List.of(hard));
        assertEquals(DesyncStatus.TRUE, ClientHorizontalCollision.hardForEveryReality(hard));
    }

    private static PredictionResult result(JavaBlockEntityPass.LastMove lastMove) {
        return result(lastMove, CLIPPED);
    }

    private static PredictionResult result(JavaBlockEntityPass.LastMove lastMove, Vec3 collided) {
        var result = mock(PredictionResult.class);
        var context = mock(SimulationContext.class);
        var valid = mock(ValidMovements.class);
        var support = new MainSupportingBlockData(null, false);
        var state = new JavaPassState(Vec3.ZERO, Vec3.ZERO, true, support, null, 0, ClientPistonDeltas.NONE);
        JavaPassAfterTravel pass = lastMove == null
                ? null
                : new JavaPassAfterTravel(
                        state,
                        Pose.STANDING,
                        new JavaBlockEntityPass.Result(state, true, false, false, false, null, null, null, lastMove),
                        true,
                        INPUT,
                        false,
                        false);
        var trace = new MovementTrace(new PredVector(collided), Vec3.ZERO, Vec3.ZERO, TRAVEL, TRAVEL, pass);
        when(result.getSimulationContext()).thenReturn(context);
        when(result.getValidMovements()).thenReturn(valid);
        when(valid.getClosestTrace()).thenReturn(trace);
        Vec3 attempted = collided == CLIPPED ? TRAVEL : collided;
        when(result.getCollideAxisData()).thenReturn(JavaMoveCollision.of(attempted, collided, new Vec3(1, 1, 1), List.of()));
        when(result.getInitialStartingVel()).thenReturn(new PredVector(Vec3.ZERO).withTravelInput(INPUT));
        return result;
    }
}
