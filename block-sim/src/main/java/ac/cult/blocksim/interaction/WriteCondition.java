package ac.cult.blocksim.interaction;

import java.util.Map;
import java.util.UUID;

/** BlockItem's explicit age component writes only when it differs from the sampled initial age. */
public record WriteCondition(UUID plantAgeSample, int excludedAge) {
    public WriteCondition {
        java.util.Objects.requireNonNull(plantAgeSample);
        if (excludedAge < 0 || excludedAge >= 25) throw new IllegalArgumentException("Only initial plant ages 0..24 are conditional");
    }
    public boolean isPresent(Map<UUID, Integer> samples) {
        Integer sample = samples.get(plantAgeSample);
        if (sample == null) throw new IllegalStateException("Initial age must be bound by the preceding placement write");
        return sample != excludedAge;
    }
}
