package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.world.FluidState;

record BedrockFluidResolution(
    Medium medium,
    FluidState fluidState,
    boolean waterContact,
    boolean lavaContact,
    Medium liquidMovementMedium
) {
    static final BedrockFluidResolution NONE = new BedrockFluidResolution(
        null,
        FluidState.NONE,
        false,
        false,
        Medium.AIR
    );
}
