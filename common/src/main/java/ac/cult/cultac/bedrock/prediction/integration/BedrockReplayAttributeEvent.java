package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.state.BedrockMovementAttributeState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.checks.impl.prediction.PredictionCommit;
import java.util.Map;

public record BedrockReplayAttributeEvent(Map<String, BedrockMovementAttributeState> attributes,
                                         boolean historical) implements BedrockReplayEvent {
    public BedrockReplayAttributeEvent { attributes = Map.copyOf(attributes); }

    @Override public BedrockMovementState state(BedrockMovementState state) {
        if (!historical) return state.withAttributes(state.attributes().replace(attributes));
        var changed = new java.util.LinkedHashMap<String, BedrockMovementAttributeState>();
        attributes.forEach((name, value) -> {
            if (!matches(state, name, value)) changed.put(name, value);
        });
        return changed.isEmpty() ? state : state.withAttributes(state.attributes().replace(changed));
    }

    @Override public boolean matchesHistory(BedrockMovementState state) {
        return historical && attributes.entrySet().stream()
                .allMatch(entry -> matches(state, entry.getKey(), entry.getValue()));
    }

    private static boolean matches(BedrockMovementState state, String name, BedrockMovementAttributeState value) {
        var previous = state.attributes().values().get(name);
        return previous != null && previous.current() == value.current();
    }

    @Override public BedrockReplayEvent ordinary() { return new BedrockReplayAttributeEvent(attributes, false); }

    public PredictionCommit apply(PredictionCommit commit) {
        var entries = BedrockProfileState.profileEntries(commit.carry()).stream()
                .map(entry -> entry.withState(state(entry.state()))).toList();
        return entries.isEmpty() ? commit : new PredictionCommit(new BedrockNextTickStates(entries),
                BedrockNextTickVelocityDerivation.profileStateVelocities(entries));
    }
}
