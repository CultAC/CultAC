package ac.cult.cultac.bedrock.prediction.simulation.travel;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockClimbMovement;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockClimbState;

final class BedrockDefaultMoveClimbVertical {
    private BedrockDefaultMoveClimbVertical() {
    }

    static Vec3d apply(
        Vec3d velocity,
        BedrockClimbState climb,
        boolean powderSnowAtFeetAscendable
    ) {
        if (climb.climbable().holdingSneak() && velocity.y() <= 0.0D) {
            return new Vec3d(velocity.x(), 0.0D, velocity.z());
        }
        if (powderSnowAtFeetAscendable || climb.fallClampApplies(velocity)) {
            return new Vec3d(
                velocity.x(),
                Math.max(velocity.y(), BedrockClimbMovement.CLIMBABLE_MAX_FALL_SPEED),
                velocity.z()
            );
        }
        return velocity;
    }
}
