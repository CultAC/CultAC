package ac.grim.grimac.bedrock.prediction.integration;

import ac.grim.grimac.checks.impl.prediction.PredictionCarry;
import java.util.List;

public record BedrockNextTickStates(List<BedrockProfileState.Entry> profileEntries) implements PredictionCarry {
    public BedrockNextTickStates {
        profileEntries = profileEntries == null ? List.of() : List.copyOf(profileEntries);
    }
}
