package ac.cult.cultac.utils.nmsutil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.utils.data.TransactionVel;
import ac.cult.cultac.utils.math.Vec3;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VelocityCandidatesTest {
    @Test
    void candidatesDropStartingVelocityFlags() {
        PredVector start = new PredVector(new Vec3(0.1, -0.078, 0.2));
        start.addPacketModifier(new TransactionVel(new Vec3(0.1, 0.4, 0.2), 0, true, false));
        assertTrue(start.isKnockback());

        PredictionResult result = mock(PredictionResult.class, RETURNS_DEEP_STUBS);
        when(result.getPlayer()).thenReturn(null);
        when(result.getInitialStartingVel()).thenReturn(start);
        when(result.getValidMovements().getPositionOnlyDelta()).thenReturn(Vec3.ZERO);
        when(result.getSimulationContext().getLastStuckSpeed()).thenReturn(null);

        Set<Vec3> candidates = VelocityCandidates.derive(result, null, new Vec3(0.1, -0.08, 0.2), new HashSet<>());

        assertTrue(candidates.contains(new Vec3(0.1, -0.078, 0.2)));
        for (Vec3 candidate : candidates) {
            assertEquals(Vec3.class, candidate.getClass(), "candidate " + candidate);
        }
    }
}
