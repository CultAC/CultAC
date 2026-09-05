package ac.cult.cultac.bedrock.prediction.simulation.frame;

record BedrockScaffoldingAction(boolean active, double moveY) {
    static final BedrockScaffoldingAction NONE = new BedrockScaffoldingAction(false, 0.0D);

    static BedrockScaffoldingAction resolve(BedrockClimbState climb) {
        if (climb.scaffolding().descendingThroughBlock()) {
            return new BedrockScaffoldingAction(true, BedrockClimbMovement.SCAFFOLDING_DESCEND_VELOCITY);
        }
        return NONE;
    }
}
