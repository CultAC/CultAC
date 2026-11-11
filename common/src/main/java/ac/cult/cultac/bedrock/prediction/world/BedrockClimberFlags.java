package ac.cult.cultac.bedrock.prediction.world;

/** The climb contact actor flags a flags update for the player sets on the client. */
public record BedrockClimberFlags(boolean inScaffolding, boolean overDescendableBlock, boolean inAscendableBlock) {}
