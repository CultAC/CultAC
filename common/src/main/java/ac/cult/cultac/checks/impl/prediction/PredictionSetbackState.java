package ac.cult.cultac.checks.impl.prediction;

import ac.cult.cultac.utils.math.Vec3;
import java.util.Objects;

/**
 * Profile-owned state captured at an accepted tick boundary for a later setback.
 * The shared setback handler may transport this value, but only the movement
 * engine that created the commit may interpret or rebase its carry.
 */
public record PredictionSetbackState(PredictionCommit commit, Vec3 position, Vec3 velocity, boolean expectedOnGround) {
    public PredictionSetbackState {
        commit = Objects.requireNonNull(commit, "commit");
        position = Objects.requireNonNull(position, "position");
        velocity = Objects.requireNonNull(velocity, "velocity");
    }
}
