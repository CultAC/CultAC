package ac.grim.grimac.bedrock.prediction.simulation.frame;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.world.BedrockClimbSurface;

public record BedrockClimbState(
    BedrockClimbSurface surface,
    BedrockScaffoldingState scaffolding,
    BedrockClimbableState climbable
) {
    public boolean inScaffolding() {
        return surface.inScaffolding();
    }

    public boolean climbing() {
        return surface.climbing();
    }

    public boolean fallClampApplies(Vec3d baseVelocity) {
        return climbing()
            && !inScaffolding()
            && !surface.fluidSuppressesFallClamp()
            && !climbable.holdingSneak()
            && baseVelocity.y() < BedrockClimbMovement.CLIMBABLE_MAX_FALL_SPEED;
    }
}
