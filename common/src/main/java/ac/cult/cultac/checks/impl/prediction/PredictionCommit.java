package ac.cult.cultac.checks.impl.prediction;

import ac.cult.cultac.utils.math.Vec3;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public record PredictionCommit(PredictionCarry carry, Set<Vec3> startingVelocities) {
    public PredictionCommit {
        if (startingVelocities == null || startingVelocities.isEmpty()) {
            startingVelocities = Set.of();
        } else if (startingVelocities.size() == 1) {
            startingVelocities = Set.of(startingVelocities.iterator().next());
        } else {
            // Iteration keeps the producer's candidate order; next-tick tie-breaks depend on it.
            var ordered = new LinkedHashSet<Vec3>(startingVelocities.size() * 2);
            for (Vec3 velocity : startingVelocities) ordered.add(Objects.requireNonNull(velocity));
            startingVelocities = Collections.unmodifiableSequencedSet(ordered);
        }
    }
}
