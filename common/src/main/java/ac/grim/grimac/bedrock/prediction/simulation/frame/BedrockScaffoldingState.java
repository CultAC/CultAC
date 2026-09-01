package ac.grim.grimac.bedrock.prediction.simulation.frame;

public enum BedrockScaffoldingState {
    NONE,
    DESCENDING;

    public boolean descendingThroughBlock() {
        return this == DESCENDING;
    }

}
