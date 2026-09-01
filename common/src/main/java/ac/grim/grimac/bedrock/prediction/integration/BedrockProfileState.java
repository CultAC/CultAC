package ac.grim.grimac.bedrock.prediction.integration;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.grim.grimac.bedrock.prediction.state.BedrockMovementState;
import ac.grim.grimac.checks.impl.prediction.PredVector;
import ac.grim.grimac.checks.impl.prediction.PredictionCarry;
import ac.grim.grimac.checks.impl.prediction.SimulationContext;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class BedrockProfileState {
    private BedrockProfileState() {
    }

    public static BedrockMovementState previousState(SimulationContext context) {
        return context == null ? null : previousState(context.getProfileCarry());
    }

    public static BedrockMovementState previousState(PredictionCarry profileStateContext) {
        Entry previousEntry = previousEntry(profileStateContext);
        return previousEntry == null ? null : previousEntry.state();
    }

    private static Entry previousEntry(SimulationContext context) {
        return context == null ? null : previousEntry(context.getProfileCarry());
    }

    private static Entry previousEntry(PredictionCarry profileStateContext) {
        List<Entry> profileEntries = profileEntries(profileStateContext);
        return profileEntries.isEmpty() ? null : profileEntries.getFirst();
    }

    public static List<Entry> profileEntries(SimulationContext context) {
        return context == null ? List.of() : profileEntries(context.getProfileCarry());
    }

    public static List<Entry> profileEntries(PredictionCarry profileStateContext) {
        if (profileStateContext instanceof BedrockNextTickStates nextTickStates) {
            return nextTickStates.profileEntries();
        }
        return List.of();
    }

    static BedrockMobJumpComponentState mobJumpComponent(SimulationContext context) {
        Entry entry = previousEntry(context);
        return entry == null ? BedrockMobJumpComponentState.DEFAULT : entry.mobJumpComponent();
    }

    public static Entry entryInLineage(PredVector startingVelocity) {
        if (startingVelocity == null) {
            return null;
        }
        BedrockStateVelocity profileStateVelocity = startingVelocity.firstInLineage(BedrockStateVelocity.class);
        return profileStateVelocity == null ? null : profileStateVelocity.entry();
    }

    public static Optional<Vec3d> trustedFeetPosition(SimulationContext context) {
        if (context != null && context.getStart() != null) {
            return Optional.of(BedrockVectorAdapter.toBedrock(context.getStart()));
        }
        BedrockMovementState state = previousState(context);
        if (state != null) {
            return Optional.of(state.physicalFeetPosition());
        }
        return Optional.empty();
    }

    public record Entry(
            BedrockMovementState state,
            BedrockMobJumpComponentState mobJumpComponent
    ) {
        public Entry {
            state = Objects.requireNonNull(state, "state");
            mobJumpComponent = mobJumpComponent == null
                    ? BedrockMobJumpComponentState.DEFAULT
                    : mobJumpComponent;
        }

        Entry withState(BedrockMovementState state) {
            if (Objects.equals(this.state, state)) {
                return this;
            }
            return new Entry(state, mobJumpComponent);
        }
    }
}
