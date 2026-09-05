package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.checks.impl.prediction.PredictionCarry;
import java.util.List;

public record BedrockNextTickStates(List<BedrockProfileState.Entry> profileEntries) implements PredictionCarry {
    public BedrockNextTickStates {
        profileEntries = profileEntries == null ? List.of() : List.copyOf(profileEntries);
    }
}
