package ac.grim.grimac.bedrock.prediction.simulation.frame;

import java.util.Objects;

public record BedrockTravelBranch(BedrockTravelSelection selection) {
    public BedrockTravelBranch {
        selection = Objects.requireNonNull(selection, "selection");
    }

    public boolean playerFlyingTravel() {
        return selection.type() == BedrockTravelType.PLAYER_FLYING;
    }

    public boolean glidingTravel() {
        return selection.type() == BedrockTravelType.GLIDING;
    }

    public boolean waterTravel() {
        return selection.type() == BedrockTravelType.WATER;
    }

    public boolean lavaTravel() {
        return selection.type() == BedrockTravelType.LAVA;
    }

    public boolean airTravel() {
        return selection.type() == BedrockTravelType.AIR;
    }

    public boolean groundTravel() {
        return selection.type() == BedrockTravelType.GROUND;
    }

    public boolean defaultMoveSystems() {
        return selection.type() == BedrockTravelType.GROUND
            || selection.type() == BedrockTravelType.AIR;
    }
}
