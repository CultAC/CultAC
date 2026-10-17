package ac.cult.cultac.checks.impl.prediction;

import ac.cult.cultac.utils.math.Vec3;
import java.util.HashSet;
import java.util.Set;

public record PredictionCommit(PredictionCarry carry, Set<Vec3> startingVelocities) {
    public PredictionCommit {
        if (startingVelocities == null) {
            startingVelocities = Set.of();
        } else if (startingVelocities instanceof HashSet<Vec3> distinct) {
            startingVelocities = Set.of(distinct.toArray(Vec3[]::new));
        } else {
            startingVelocities = Set.copyOf(startingVelocities);
        }
    }
}
