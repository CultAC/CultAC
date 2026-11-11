package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.data.CollideAxisData.CollideResult;

final class BedrockCollisionAxisData {
    private BedrockCollisionAxisData() {}

    static CollideAxisData neutral() {
        return new CollideAxisData(
                new CollideAxisData.CollideResult(false, 0.0D),
                new CollideAxisData.CollideResult(false, 0.0D),
                new CollideAxisData.CollideResult(false, 0.0D),
                new CollideAxisData.CollideResult(false, 0.0D));
    }

    /**
     * The collision of {@code movement}'s own travel: each axis holds the collided move, colliding where the travel's
     * collision flags say it stopped, on the side its attempted move went.
     */
    static CollideAxisData of(BedrockMovementResult movement) {
        Vec3d previous = movement.previousState().physicalFeetPosition();
        Vec3d move = movement.predictedPosition().subtract(previous);
        Vec3d attempted = movement.rawPredictedPhysicalFeetPosition().subtract(previous);
        BedrockCollisionFlags flags = movement.predictedState().collisionFlags();
        boolean below = flags.verticalCollision() && flags.verticalCollisionBelow();
        return new CollideAxisData(
                horizontal(flags.xCollision(), move.x(), attempted.x()),
                new CollideResult(flags.verticalCollision() && !below, move.y()),
                new CollideResult(below, move.y()),
                horizontal(flags.zCollision(), move.z(), attempted.z()));
    }

    private static CollideResult horizontal(boolean collided, double move, double attempted) {
        return new CollideResult(collided, move, collided ? (int) Math.signum(attempted) : 0);
    }
}
