package ac.cult.cultac.bedrock.prediction.simulation.collision;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.travel.BedrockMoveRequest;
import ac.cult.cultac.bedrock.prediction.simulation.travel.BedrockResolvedMove;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BlockCollision;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;

public final class BedrockSneakEdgeMovement {
    private static final float BACKOFF_STEP = 0.05F;
    private static final float SUPPORT_INSET = 0.025F;
    private static final float STEP_REACH_SCALE = 1.01F;
    private static final float STOPPED_AXIS_EPSILON = Math.ulp(1.0F);

    private BedrockSneakEdgeMovement() {
    }

    public static BedrockMoveRequest applyBeforeCollision(
        BedrockMovementState current,
        boolean sneaking,
        PlayerDimensionsState dimensions,
        double maxUpStep,
        BedrockMoveRequest request,
        BlockCollisionWorld blockCollisionWorld
    ) {
        // Nearby support alone does not enable edge protection while airborne.
        if (!sneaking || !current.collisionFlags().onGround()) {
            return request;
        }
        WorldCollisionBox supportBox = supportBox(current, dimensions, maxUpStep, blockCollisionWorld.coordinateFrame());
        Vec3d move = backOffFromEdge(request.move(), supportBox, blockCollisionWorld);
        Vec3d velocity = request.collisionInputVelocity();
        // Partial backoff retains momentum; fully stopped axes lose it before collision.
        Vec3d nextVelocity = new Vec3d(
            Math.abs(move.x()) <= STOPPED_AXIS_EPSILON ? 0.0D : velocity.x(),
            velocity.y(),
            Math.abs(move.z()) <= STOPPED_AXIS_EPSILON ? 0.0D : velocity.z());
        Vec3d feet = current.physicalFeetPosition();
        return new BedrockMoveRequest(
            new BedrockResolvedMove(request.lavaSwimUpApplied(), move, nextVelocity),
            new Vec3d(feet.x() + move.x(), request.requestedPosition().y(), feet.z() + move.z()));
    }

    private static Vec3d backOffFromEdge(
        Vec3d move,
        WorldCollisionBox supportBox,
        BlockCollisionWorld blockCollisionWorld
    ) {
        float backedOffX = (float) move.x();
        float backedOffZ = (float) move.z();
        float stepX = Math.signum(backedOffX) * BACKOFF_STEP;
        float stepZ = Math.signum(backedOffZ) * BACKOFF_STEP;

        while (backedOffX != 0.0D && canFallAt(supportBox, backedOffX, 0.0F, blockCollisionWorld)) {
            backedOffX = approachZero(backedOffX, stepX);
        }
        while (backedOffZ != 0.0D && canFallAt(supportBox, 0.0F, backedOffZ, blockCollisionWorld)) {
            backedOffZ = approachZero(backedOffZ, stepZ);
        }
        while ((backedOffX != 0.0D || backedOffZ != 0.0D)
            && canFallAt(supportBox, backedOffX, backedOffZ, blockCollisionWorld)) {
            if (Math.abs(backedOffX) <= BACKOFF_STEP) {
                backedOffX = 0.0F;
            } else {
                backedOffX -= stepX;
            }
            if (Math.abs(backedOffZ) <= BACKOFF_STEP) {
                backedOffZ = 0.0F;
            } else {
                backedOffZ -= stepZ;
            }
        }
        return new Vec3d(backedOffX, move.y(), backedOffZ);
    }

    private static boolean canFallAt(
        WorldCollisionBox supportBox,
        float deltaX,
        float deltaZ,
        BlockCollisionWorld blockCollisionWorld
    ) {
        WorldCollisionBox stepDownBox = BedrockCollisionSweep.moveBedrock(
            supportBox, deltaX, 0.0D, deltaZ, blockCollisionWorld.coordinateFrame());
        for (BlockCollision obstacle : BedrockCollisionSweep.collisionObstacles(blockCollisionWorld)) {
            if (stepDownBox.intersects(obstacle.box())) {
                return false;
            }
        }
        return true;
    }

    private static WorldCollisionBox supportBox(BedrockMovementState current, PlayerDimensionsState dimensions,
                                                 double maxUpStep, BedrockCoordinateFrame frame) {
        WorldCollisionBox box = current.collisionBox(dimensions);
        double minX = frame.roundX(box.minX() + SUPPORT_INSET);
        double maxX = frame.roundX(box.maxX() - SUPPORT_INSET);
        double minZ = frame.roundZ(box.minZ() + SUPPORT_INSET);
        double maxZ = frame.roundZ(box.maxZ() - SUPPORT_INSET);
        // A narrow actor's inset collapses to its center instead of inverting.
        if (minX > maxX) {
            minX = maxX = frame.originX() + (double) ((frame.localX(box.minX()) + frame.localX(box.maxX())) * 0.5F);
        }
        if (minZ > maxZ) {
            minZ = maxZ = frame.originZ() + (double) ((frame.localZ(box.minZ()) + frame.localZ(box.maxZ())) * 0.5F);
        }
        float stepDown = (float) maxUpStep * STEP_REACH_SCALE;
        return new WorldCollisionBox(minX, (float) (box.minY() - stepDown), minZ,
            maxX, (float) (box.maxY() - stepDown), maxZ);
    }

    private static float approachZero(float value, float step) {
        if (Math.abs(value) <= BACKOFF_STEP) {
            return 0.0F;
        }
        return value - step;
    }
}
