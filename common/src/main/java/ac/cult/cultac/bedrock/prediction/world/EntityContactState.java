package ac.cult.cultac.bedrock.prediction.world;

import java.util.Objects;

public record EntityContactState(DolphinBoostState dolphinBoostState, boolean passenger, SpinContact spinContact) {
    public static final EntityContactState NONE = new EntityContactState(DolphinBoostState.NONE);

    public EntityContactState {
        dolphinBoostState = Objects.requireNonNull(dolphinBoostState, "dolphinBoostState");
        spinContact = Objects.requireNonNull(spinContact, "spinContact");
    }

    public EntityContactState(DolphinBoostState dolphinBoostState) {
        this(dolphinBoostState, false);
    }

    public EntityContactState(DolphinBoostState dolphinBoostState, boolean passenger) {
        this(dolphinBoostState, passenger, SpinContact.NONE);
    }

    public boolean dolphinBoostAvailable() {
        return dolphinBoostState.boostAvailable();
    }

    public EntityContactState withSpinContact(SpinContact spinContact) {
        return new EntityContactState(dolphinBoostState, passenger, spinContact);
    }
}
