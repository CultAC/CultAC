package ac.grim.grimac.bedrock.prediction.world;

import java.util.Objects;

public record EntityContactState(
    DolphinBoostState dolphinBoostState
) {
    public static final EntityContactState NONE =
        new EntityContactState(DolphinBoostState.NONE);

    public EntityContactState {
        dolphinBoostState = Objects.requireNonNull(dolphinBoostState, "dolphinBoostState");
    }

    public boolean dolphinBoostAvailable() {
        return dolphinBoostState.boostAvailable();
    }
}
