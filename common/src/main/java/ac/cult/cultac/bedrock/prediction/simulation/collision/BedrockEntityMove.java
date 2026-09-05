package ac.cult.cultac.bedrock.prediction.simulation.collision;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BlockCollision;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import java.util.List;
import java.util.Optional;

public final class BedrockEntityMove {
    private static final double EPSILON = BedrockCollisionSweep.EPSILON;

    private BedrockEntityMove() {
    }

    public static Result move(
        BedrockMovementState current,
        BedrockInputFrame frame,
        BlockCollisionWorld blockCollisionWorld,
        PlayerDimensionsState dimensions,
        Vec3d requestedPosition,
        Vec3d velocity,
        double moveY
    ) {
        return move(current, frame, blockCollisionWorld, dimensions, requestedPosition, velocity, moveY, false);
    }

    public static Result move(
        BedrockMovementState current,
        BedrockInputFrame frame,
        BlockCollisionWorld blockCollisionWorld,
        PlayerDimensionsState dimensions,
        Vec3d requestedPosition,
        Vec3d velocity,
        double moveY,
        boolean canStep
    ) {
        return move(
            current,
            frame,
            blockCollisionWorld,
            dimensions,
            requestedPosition,
            velocity,
            moveY,
            canStep,
            BedrockSimulation.DEFAULT_MAX_AUTO_STEP
        );
    }

    public static Result move(
        BedrockMovementState current,
        BedrockInputFrame frame,
        BlockCollisionWorld blockCollisionWorld,
        PlayerDimensionsState dimensions,
        Vec3d requestedPosition,
        Vec3d velocity,
        double moveY,
        boolean canStep,
        double maxUpStep
    ) {
        if (blockCollisionWorld.isEmpty()) {
            return noCollisionResult(requestedPosition, velocity);
        }

        Vec3d requestedDelta = requestedPosition.subtract(current.physicalFeetPosition());
        ActorMove actorMove = actorMove(
            current,
            requestedDelta,
            blockCollisionWorld,
            dimensions
        );
        CollisionMove collisionMove = autoStep(current, actorMove, dimensions, canStep, maxUpStep);
        return finalizeMove(
            current,
            frame,
            dimensions,
            velocity,
            actorMove.requestedDelta(),
            collisionMove
        );
    }

    public static Result noCollisionResult(Vec3d requestedPosition, Vec3d velocity) {
        return new Result(
            requestedPosition,
            velocity,
            false,
            false,
            false,
            false,
            BedrockCollisionFlags.AIR,
            false,
            false,
            false,
            false,
            false
        );
    }

    public static Result finalizeMove(
        BedrockMovementState current,
        BedrockInputFrame frame,
        PlayerDimensionsState dimensions,
        Vec3d velocity,
        Vec3d requestedDelta,
        CollisionMove collisionMove
    ) {
        FinalizedMove finalizedMove = finalizeMoveOutput(
            current,
            dimensions,
            velocity,
            requestedDelta,
            collisionMove
        );
        return verticalCollision(current, frame, collisionMove, finalizedMove, requestedDelta.y());
    }

    public static FinalizedMove finalizeMoveOutput(
        BedrockMovementState current,
        PlayerDimensionsState dimensions,
        Vec3d velocity,
        Vec3d requestedDelta,
        CollisionMove collisionMove
    ) {
        BedrockBlockCollisionResolver.Result blockCollision = BedrockBlockCollisionResolver.fromMove(
            requestedDelta,
            velocity,
            collisionMove.obstacles(),
            dimensions,
            collisionMove.baseMove(),
            collisionMove.selectedMove(),
            collisionMove.steppedUp()
        );
        Vec3d nextPosition = blockCollision.position();
        Vec3d nextVelocity = blockCollision.velocity();
        BedrockCollisionFlags collisionFlags = finalizedCollisionFlags(
            current,
            requestedDelta,
            blockCollision,
            false
        );
        return new FinalizedMove(
            blockCollision,
            nextPosition,
            nextVelocity,
            collisionFlags,
            collisionFlags.onGround(),
            collisionMove.steppedUp(),
            collisionMove.stepRetryAllowed(),
            blockCollision.verticalCollision()
        );
    }

    public static Result verticalCollision(
        BedrockMovementState current,
        BedrockInputFrame frame,
        CollisionMove collisionMove,
        FinalizedMove finalizedMove,
        double fallingVelocityY
    ) {
        BedrockBlockCollisionResolver.Result blockCollision = finalizedMove.blockCollision();
        Vec3d nextVelocity = finalizedMove.velocity();
        boolean onGround = finalizedMove.onGround();
        boolean verticalCollision = finalizedMove.verticalCollision();
        return new Result(
            finalizedMove.position(),
            nextVelocity,
            blockCollision.xCollision(),
            blockCollision.zCollision(),
            blockCollision.horizontalCollisionFlag(),
            blockCollision.horizontalBlockContact(),
            finalizedMove.collisionFlags(),
            onGround,
            finalizedMove.steppedUp(),
            finalizedMove.stepRetryAllowed(),
            verticalCollision,
            false
        );
    }

    public static ActorMove actorMove(
        BedrockMovementState current,
        Vec3d requestedDelta,
        BlockCollisionWorld blockCollisionWorld,
        PlayerDimensionsState dimensions
    ) {
        return actorMove(
            current,
            requestedDelta,
            BedrockCollisionSweep.collisionObstacles(blockCollisionWorld),
            dimensions
        );
    }

    static ActorMove actorMove(
        BedrockMovementState current,
        Vec3d requestedDelta,
        List<BlockCollision> obstacles,
        PlayerDimensionsState dimensions
    ) {
        BedrockCollisionSweep.MoveResult baseMove = BedrockCollisionSweep.sweep(
            current.physicalFeetPosition(),
            requestedDelta,
            obstacles,
            dimensions
        );
        return new ActorMove(requestedDelta, obstacles, baseMove);
    }

    public static AutoStepRequest autoStepRequest(
        BedrockMovementState current,
        ActorMove actorMove,
        boolean canStep,
        double maxUpStep
    ) {
        Vec3d requestedDelta = actorMove.requestedDelta();
        BedrockCollisionSweep.MoveResult baseMove = actorMove.baseMove();
        boolean groundedOrDownwardCollision = current.collisionFlags().onGround()
            || baseMove.yCollision() && requestedDelta.y() < 0.0D;
        boolean horizontalMovementChanged = horizontalMovementChanged(requestedDelta, baseMove);
        boolean stepRetryAllowed = maxUpStep > 0.0D
            && groundedOrDownwardCollision
            && horizontalMovementChanged;
        return new AutoStepRequest(canStep && stepRetryAllowed, stepRetryAllowed);
    }

    public static CollisionMove autoStep(
        BedrockMovementState current,
        ActorMove actorMove,
        PlayerDimensionsState dimensions,
        AutoStepRequest autoStepRequest,
        double maxUpStep
    ) {
        Vec3d requestedDelta = actorMove.requestedDelta();
        BedrockCollisionSweep.MoveResult baseMove = actorMove.baseMove();
        BedrockCollisionSweep.MoveResult selectedMove = baseMove;
        Optional<BedrockCollisionSweep.MoveResult> stepMove = Optional.empty();
        if (autoStepRequest.requested()) {
            stepMove = BedrockAutoStepResolver.resolve(
                current,
                requestedDelta,
                baseMove,
                actorMove.obstacles(),
                dimensions,
                maxUpStep
            );
        }
        boolean steppedUp = false;
        if (autoStepRequest.requested() && stepMove.isPresent()) {
            selectedMove = stepMove.get();
            steppedUp = true;
        }
        return new CollisionMove(actorMove.obstacles(), baseMove, selectedMove, steppedUp, autoStepRequest.stepRetryAllowed());
    }

    public static CollisionMove autoStep(
        BedrockMovementState current,
        ActorMove actorMove,
        PlayerDimensionsState dimensions,
        boolean canStep,
        double maxUpStep
    ) {
        AutoStepRequest autoStepRequest = autoStepRequest(current, actorMove, canStep, maxUpStep);
        return autoStep(current, actorMove, dimensions, autoStepRequest, maxUpStep);
    }

    public static CollisionMove collide(
        BedrockMovementState current,
        Vec3d requestedDelta,
        BlockCollisionWorld blockCollisionWorld,
        PlayerDimensionsState dimensions,
        boolean canStep,
        double maxUpStep
    ) {
        return collide(
            current,
            requestedDelta,
            BedrockCollisionSweep.collisionObstacles(blockCollisionWorld),
            dimensions,
            canStep,
            maxUpStep
        );
    }

    static CollisionMove collide(
        BedrockMovementState current,
        Vec3d requestedDelta,
        List<BlockCollision> obstacles,
        PlayerDimensionsState dimensions,
        boolean canStep,
        double maxUpStep
    ) {
        ActorMove actorMove = actorMove(current, requestedDelta, obstacles, dimensions);
        return autoStep(current, actorMove, dimensions, canStep, maxUpStep);
    }

    private static boolean horizontalMovementChanged(
        Vec3d requestedDelta,
        BedrockCollisionSweep.MoveResult move
    ) {
        double requestedX = BedrockCollisionSweep.f(requestedDelta.x());
        double requestedZ = BedrockCollisionSweep.f(requestedDelta.z());
        return Math.abs(requestedX - move.appliedDelta().x()) > EPSILON
            || Math.abs(requestedZ - move.appliedDelta().z()) > EPSILON;
    }

    public static boolean horizontalBlockContactAt(
        Vec3d feet,
        BlockCollisionWorld blockCollisionWorld,
        PlayerDimensionsState dimensions
    ) {
        if (blockCollisionWorld.isEmpty()) {
            return false;
        }
        return BedrockBlockCollisionResolver.hasHorizontalBlockContact(
            feet,
            BedrockCollisionSweep.collisionObstacles(blockCollisionWorld),
            dimensions
        );
    }

    public static BedrockCollisionFlags finalizedCollisionFlags(
        BedrockMovementState previous,
        Vec3d requestedDelta,
        BedrockBlockCollisionResolver.Result collision,
        boolean liquidClimbOut
    ) {
        return new BedrockCollisionFlags(
            collision.onGround() || preservesGround(previous, requestedDelta, collision.verticalCollision()),
            collision.horizontalCollisionFlag(),
            collision.verticalCollision(),
            collision.horizontalBlockContact(),
            liquidClimbOut,
            collision.onGround(),
            collision.xCollision(),
            collision.zCollision()
        );
    }

    public static boolean preservesGround(
        BedrockMovementState previous,
        Vec3d requestedDelta,
        boolean verticalCollision
    ) {
        return previous.movementGrounded()
            && !verticalCollision
            && Math.abs(requestedDelta.y()) <= BedrockCollisionSweep.EPSILON;
    }

    public record ActorMove(
        Vec3d requestedDelta,
        List<BlockCollision> obstacles,
        BedrockCollisionSweep.MoveResult baseMove
    ) {
    }

    public record AutoStepRequest(boolean requested, boolean stepRetryAllowed) {
    }

    public record CollisionMove(
        List<BlockCollision> obstacles,
        BedrockCollisionSweep.MoveResult baseMove,
        BedrockCollisionSweep.MoveResult selectedMove,
        boolean steppedUp,
        boolean stepRetryAllowed
    ) {
    }

    public record FinalizedMove(
        BedrockBlockCollisionResolver.Result blockCollision,
        Vec3d position,
        Vec3d velocity,
        BedrockCollisionFlags collisionFlags,
        boolean onGround,
        boolean steppedUp,
        boolean stepRetryAllowed,
        boolean verticalCollision
    ) {
    }

    public record Result(
        Vec3d position,
        Vec3d velocity,
        boolean xCollision,
        boolean zCollision,
        boolean horizontalCollisionFlag,
        boolean horizontalBlockContact,
        BedrockCollisionFlags collisionFlags,
        boolean onGround,
        boolean steppedUp,
        boolean stepRetryAllowed,
        boolean verticalCollision,
        boolean standingBounceBounced
    ) {
    }
}
