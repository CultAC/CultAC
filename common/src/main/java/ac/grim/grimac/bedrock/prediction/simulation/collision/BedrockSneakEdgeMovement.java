package ac.grim.grimac.bedrock.prediction.simulation.collision;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.grim.grimac.bedrock.prediction.model.Medium;
import ac.grim.grimac.bedrock.prediction.model.PlayerDimensionsState;
import ac.grim.grimac.bedrock.prediction.state.BedrockMovementState;
import ac.grim.grimac.bedrock.prediction.world.BlockCollision;
import ac.grim.grimac.bedrock.prediction.world.BlockCollisionWorld;

public final class BedrockSneakEdgeMovement {
    private static final double BACKOFF_STEP = 0.05000000074505806D;
    private static final double MAX_STEP_DOWN = 0.5625D;

    private BedrockSneakEdgeMovement() {
    }

    public static Vec3d applyBeforeCollision(
        BedrockMovementState current,
        boolean sneaking,
        Vec3d move,
        Vec3d nextPosition,
        BlockCollisionWorld blockCollisionWorld
    ) {
        if (!sneaking
            || !isAboveGround(current, blockCollisionWorld)
            || (move.x() == 0.0D && move.z() == 0.0D)) {
            return nextPosition;
        }
        Vec3d backedOffHorizontalPosition = backOffFromEdge(
            current,
            move.x(),
            move.z(),
            blockCollisionWorld
        );
        return new Vec3d(
            backedOffHorizontalPosition.x(),
            nextPosition.y(),
            backedOffHorizontalPosition.z()
        );
    }

    public static Vec3d backOffFromEdge(
        BedrockMovementState current,
        double moveX,
        double moveZ,
        BlockCollisionWorld blockCollisionWorld
    ) {
        if (blockCollisionWorld.isEmpty()) {
            return current.physicalFeetPosition().add(new Vec3d(moveX, 0.0D, moveZ));
        }

        double backedOffX = moveX;
        double backedOffZ = moveZ;
        double stepX = Math.signum(backedOffX) * BACKOFF_STEP;
        double stepZ = Math.signum(backedOffZ) * BACKOFF_STEP;

        while (backedOffX != 0.0D && canFallAt(current, backedOffX, 0.0D, blockCollisionWorld)) {
            backedOffX = approachZero(backedOffX, stepX);
        }
        while (backedOffZ != 0.0D && canFallAt(current, 0.0D, backedOffZ, blockCollisionWorld)) {
            backedOffZ = approachZero(backedOffZ, stepZ);
        }
        while (backedOffX != 0.0D
            && backedOffZ != 0.0D
            && canFallAt(current, backedOffX, backedOffZ, blockCollisionWorld)) {
            if (Math.abs(backedOffX) <= BACKOFF_STEP) {
                backedOffX = 0.0D;
            } else {
                backedOffX -= stepX;
            }
            if (Math.abs(backedOffZ) <= BACKOFF_STEP) {
                backedOffZ = 0.0D;
            } else {
                backedOffZ -= stepZ;
            }
        }
        return current.physicalFeetPosition().add(new Vec3d(backedOffX, 0.0D, backedOffZ));
    }

    private static boolean canFallAt(
        BedrockMovementState current,
        double deltaX,
        double deltaZ,
        BlockCollisionWorld blockCollisionWorld
    ) {
        return !hasStepDownSupport(
            current.physicalFeetPosition().add(new Vec3d(deltaX, 0.0D, deltaZ)),
            blockCollisionWorld
        );
    }

    private static boolean isAboveGround(
        BedrockMovementState current,
        BlockCollisionWorld blockCollisionWorld
    ) {
        return current.movementBranch() == Medium.GROUND
            || hasStepDownSupport(current.physicalFeetPosition(), blockCollisionWorld);
    }

    private static boolean hasStepDownSupport(
        Vec3d feet,
        BlockCollisionWorld blockCollisionWorld
    ) {
        if (blockCollisionWorld.isEmpty()) {
            return false;
        }
        WorldCollisionBox stepDownBox = BedrockCollisionSweep.playerBox(
            feet,
            PlayerDimensionsState.DEFAULT
        ).move(0.0D, -MAX_STEP_DOWN, 0.0D);
        for (BlockCollision obstacle : BedrockCollisionSweep.collisionObstacles(blockCollisionWorld)) {
            if (stepDownBox.intersects(obstacle.box())) {
                return true;
            }
        }
        return false;
    }

    private static double approachZero(double value, double step) {
        if (Math.abs(value) <= BACKOFF_STEP) {
            return 0.0D;
        }
        return value - step;
    }
}
