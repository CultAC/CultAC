package ac.cult.cultac.bedrock.prediction.api;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import java.util.Objects;

public record BedrockFluidMovementSource(
    Medium medium,
    Vec3d direction,
    double pushPerTick,
    Vec3d appliedDelta
) {
    public static final BedrockFluidMovementSource NONE = new BedrockFluidMovementSource(
        Medium.AIR,
        Vec3d.ZERO,
        0.0D,
        Vec3d.ZERO
    );

    public BedrockFluidMovementSource {
        medium = Objects.requireNonNull(medium, "medium");
        direction = Objects.requireNonNull(direction, "direction");
        appliedDelta = Objects.requireNonNull(appliedDelta, "appliedDelta");
        if (!Double.isFinite(pushPerTick) || pushPerTick < 0.0D) {
            throw new IllegalArgumentException("pushPerTick must be finite and non-negative");
        }
    }

    public boolean active() {
        return pushPerTick > 0.0D
            && (appliedDelta.x() != 0.0D || appliedDelta.y() != 0.0D || appliedDelta.z() != 0.0D);
    }
}
