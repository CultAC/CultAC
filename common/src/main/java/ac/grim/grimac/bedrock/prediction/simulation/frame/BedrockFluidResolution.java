package ac.grim.grimac.bedrock.prediction.simulation.frame;

import ac.grim.grimac.bedrock.prediction.model.Medium;
import ac.grim.grimac.bedrock.prediction.world.FluidState;

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
