package ac.grim.grimac.bedrock.prediction.simulation.frame;

import ac.grim.grimac.bedrock.prediction.model.Medium;

public record BedrockTravelSelection(
    BedrockTravelType type,
    Medium movementBranch
) {
    public boolean liquidTravelActive() {
        return type == BedrockTravelType.WATER || type == BedrockTravelType.LAVA;
    }
}
