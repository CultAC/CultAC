package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.state.BedrockHorseState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;

/** Horse control surrounds the shared living-entity travel stages. */
public final class BedrockHorseMovement {
    private BedrockHorseMovement() { }

    public static float maxUpStep(BedrockMovementState state, BedrockMovementContext context) {
        return BedrockJumpPreventionResolver.resolve(context, state.physicalFeetPosition(),
                state.collisionFlags().onGround()).active() ? 0.5625F : 1.0625F;
    }

    public static float yawAfterControl(float previousYaw, float riderYaw) {
        float difference = wrapDegrees(riderYaw - previousYaw);
        float rate = Math.max(0.18F, (45.0F - Math.min(Math.abs(difference), 45.0F)) / 90.0F) * 0.7F;
        return wrapDegrees(previousYaw + difference * rate);
    }

    private static float wrapDegrees(float angle) {
        float wrapped = angle % 360.0F;
        if (wrapped >= 180.0F) wrapped -= 360.0F;
        if (wrapped < -180.0F) wrapped += 360.0F;
        return wrapped;
    }

    static Jump applyJump(BedrockTravelInput input, BedrockFrameFacts facts, Vec3d velocity) {
        BedrockHorseState horse = input.previousState().horse().requestJump();
        if (!input.options().travelActive() || !horse.canLaunch(input.previousState().collisionFlags().onGround())) {
            return new Jump(horse, velocity, false);
        }
        var prevention = BedrockJumpPreventionResolver.resolve(facts.context(),
                input.previousState().physicalFeetPosition(), true);
        float blockFactor = prevention.active() ? 0.6F : 1.0F;
        float scale = horse.pendingJump();
        float vertical = (facts.context().attributeState().jumpStrength() * scale) * blockFactor
                + (facts.effectState().jumpBoostLevel() * 0.1F) * blockFactor;
        float x = (float) velocity.x();
        float z = (float) velocity.z();
        if (horse.forwardJump()) {
            float radians = input.inputFrame().yaw() * BedrockMath.DEGREES_TO_RADIANS;
            x += (-0.4F * BedrockMath.sin(radians)) * scale;
            z += (0.4F * BedrockMath.cos(radians)) * scale;
        }
        return new Jump(horse.launched(), new Vec3d(x, vertical, z), true);
    }

    record Jump(BedrockHorseState state, Vec3d velocity, boolean launched) { }
}
