package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.world.BedrockClimbSurface;

public record BedrockClimbState(
        BedrockClimbSurface surface, BedrockScaffoldingState scaffolding, BedrockClimbableState climbable) {
    public boolean inScaffolding() {
        return surface.inScaffolding();
    }

    public boolean climbing() {
        return surface.climbing();
    }

    /** A descent inside scaffolding drags its descend velocity and takes no gravity; one into powder snow does not. */
    public boolean descendingInsideScaffolding() {
        return scaffolding.descendingThroughBlock() && inScaffolding();
    }

    public boolean fallClampApplies(Vec3d baseVelocity) {
        return climbing()
                && !inScaffolding()
                && !surface.fluidSuppressesFallClamp()
                && !climbable.holdingSneak()
                && baseVelocity.y() < BedrockClimbMovement.CLIMBABLE_MAX_FALL_SPEED;
    }
}
