package ac.cult.cultac.bedrock.prediction.simulation.travel;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockAerialMovement;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockClimbState;

final class BedrockTravelMoveVector {
    private BedrockTravelMoveVector() {
    }

    static Step resolve(Vec3d baseVelocity, BedrockClimbState climb) {
        double moveX = baseVelocity.x();
        double moveZ = baseVelocity.z();
        boolean descendingThroughBlock = climb.scaffolding().descendingThroughBlock();

        double moveY = baseVelocity.y();
        double collisionInputY = descendingThroughBlock
            ? BedrockAerialMovement.airDraggedVelocityWithoutGravity(moveY)
            : moveY;

        Vec3d move = new Vec3d(moveX, moveY, moveZ);
        return new Step(move, new Vec3d(moveX, collisionInputY, moveZ));
    }

    record Step(Vec3d move, Vec3d collisionInputVelocity) {
    }
}
