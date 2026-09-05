package ac.cult.cultac.bedrock.prediction.simulation.frame;

public enum BedrockScaffoldingState {
    NONE,
    DESCENDING;

    public boolean descendingThroughBlock() {
        return this == DESCENDING;
    }

}
