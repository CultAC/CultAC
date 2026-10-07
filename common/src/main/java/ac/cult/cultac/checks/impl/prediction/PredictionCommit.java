package ac.cult.cultac.checks.impl.prediction;

import ac.cult.cultac.utils.math.Vec3;
import java.util.Set;

public record PredictionCommit(PredictionCarry carry, Set<Vec3> startingVelocities) {
    public PredictionCommit {
        startingVelocities = startingVelocities == null ? Set.of() : Set.copyOf(startingVelocities);
    }
}
