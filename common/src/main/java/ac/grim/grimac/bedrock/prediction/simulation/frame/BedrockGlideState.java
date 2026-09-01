package ac.grim.grimac.bedrock.prediction.simulation.frame;

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
