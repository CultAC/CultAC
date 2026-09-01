package ac.grim.grimac.checks.impl.prediction;

import java.util.Set;
import net.minecraft.world.phys.Vec3;

public record PredictionCommit(PredictionCarry carry, Set<Vec3> startingVelocities) {
    public PredictionCommit {
        startingVelocities = startingVelocities == null ? Set.of() : Set.copyOf(startingVelocities);
    }
}
