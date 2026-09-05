package ac.cult.cultac.bedrock.prediction.simulation.frame;

public record BedrockGlideState(
    boolean actorStateAfterActions,
    boolean activeAtTravelSensing,
    boolean activeAtGlideInputSystem,
    boolean requestAfterActions
) {
    public boolean activeAfterActions() {
        return actorStateAfterActions;
    }
}
