package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.world.JumpPreventionState;
import java.util.Objects;

final class BedrockJumpMovement {
    private static final float JUMP_BOOST_BONUS_PER_LEVEL = 0.1F;
    private static final float JUMP_PREVENTION_BLOCKED_MULTIPLIER = 0.6F;

    private BedrockJumpMovement() {
    }

    static Vec3d groundLaunchVelocity(
        Vec3d currentVelocity,
        BedrockInputFrame frame,
        double jumpVelocity,
        boolean sprintJumpImpulseActive
    ) {
        double launchVelocityY = Math.max(currentVelocity.y(), jumpVelocity);
        if (!sprintJumpImpulseActive) {
            return new Vec3d(currentVelocity.x(), launchVelocityY, currentVelocity.z());
        }
        float yawRadians = frame.yaw() * BedrockMath.DEGREES_TO_RADIANS;
        return new Vec3d(
            currentVelocity.x() - BedrockMath.sin(yawRadians) * 0.2D,
            launchVelocityY,
            currentVelocity.z() + BedrockMath.cos(yawRadians) * 0.2D
        );
    }

    static double jumpVelocity(
        BedrockEffectState effectState,
        float jumpStrength,
        JumpPreventionState jumpPreventionState
    ) {
        int jumpBoostLevel = effectState.jumpBoostLevel();
        float baseImpulse = jumpBoostLevel == 0
            ? jumpStrength
            : (float) (jumpStrength + JUMP_BOOST_BONUS_PER_LEVEL * jumpBoostLevel);
        return (float) (baseImpulse * jumpPreventionMultiplier(jumpPreventionState));
    }

    private static float jumpPreventionMultiplier(JumpPreventionState jumpPreventionState) {
        Objects.requireNonNull(jumpPreventionState, "jumpPreventionState");
        return jumpPreventionState.active() ? JUMP_PREVENTION_BLOCKED_MULTIPLIER : 1.0F;
    }
}
