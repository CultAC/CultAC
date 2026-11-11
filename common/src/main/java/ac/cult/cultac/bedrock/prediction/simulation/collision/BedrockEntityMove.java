package ac.cult.cultac.bedrock.prediction.simulation.collision;

import ac.cult.cultac.bedrock.prediction.api.BedrockActorOrderRange;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockActorBox;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
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

    private BedrockEntityMove() {}

    public static Result move(
            BedrockMovementState current,
            BedrockInputFrame frame,
            BlockCollisionWorld blockCollisionWorld,
            PlayerDimensionsState dimensions,
            Vec3d requestedPosition,
            Vec3d velocity,
            double moveY) {
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
            boolean canStep) {
        return move(
                current,
                frame,
                blockCollisionWorld,
                dimensions,
                requestedPosition,
                velocity,
                moveY,
                canStep,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                false);
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
            double maxUpStep,
            boolean flying) {
        return move(
                current,
                frame,
                blockCollisionWorld,
                dimensions,
                requestedPosition,
                velocity,
                moveY,
                canStep,
                maxUpStep,
                flying,
                BedrockCollisionFetchBox.of(
                        current.collisionBox(dimensions),
                        requestedPosition.subtract(current.physicalFeetPosition()),
                        maxUpStep,
                        current.coordinateFrame()));
    }

    /** Moves through the shapes {@code fetchBox} fetches; contact with the blocks around the result reads them all. */
    public static Result move(
            BedrockMovementState current,
            BedrockInputFrame frame,
            BlockCollisionWorld blockCollisionWorld,
            PlayerDimensionsState dimensions,
            Vec3d requestedPosition,
            Vec3d velocity,
            double moveY,
            boolean canStep,
            double maxUpStep,
            boolean flying,
            WorldCollisionBox fetchBox) {
        return move(
                current,
                frame,
                blockCollisionWorld,
                dimensions,
                requestedPosition,
                velocity,
                moveY,
                canStep,
                maxUpStep,
                flying,
                fetchBox,
                0);
    }

    /**
     * Moves meeting the solid actors in {@code solidActorOrder} (see {@link BedrockSolidActorOrders}), 0 being the
     * collected order. Its result names the order and the range the actors' order gives its end.
     */
    public static Result move(
            BedrockMovementState current,
            BedrockInputFrame frame,
            BlockCollisionWorld blockCollisionWorld,
            PlayerDimensionsState dimensions,
            Vec3d requestedPosition,
            Vec3d velocity,
            double moveY,
            boolean canStep,
            double maxUpStep,
            boolean flying,
            WorldCollisionBox fetchBox,
            int solidActorOrder) {
        if (blockCollisionWorld.isEmpty()) {
            WorldCollisionBox box = BedrockActorBox.move(
                    current.collisionBox(dimensions),
                    requestedPosition.subtract(current.physicalFeetPosition()),
                    current.coordinateFrame());
            return noCollisionResult(BedrockActorBox.feet(box, current.coordinateFrame()), velocity)
                    .withBox(box);
        }

        Vec3d requestedDelta = requestedPosition.subtract(current.physicalFeetPosition());
        List<BlockCollision> shapes = BedrockCollisionSweep.collisionObstacles(blockCollisionWorld);
        List<BlockCollision> obstacles = BedrockCollisionFetchBox.fetched(shapes, fetchBox);
        if (solidActorOrder != 0) {
            obstacles = BedrockSolidActorOrders.arrange(
                    obstacles,
                    solidActorOrder,
                    current.collisionBox(dimensions),
                    current.coordinateFrame(),
                    BedrockCollisionClipper.maximumDepenetration(current, obstacles));
        }
        ActorMove actorMove = actorMove(current, requestedDelta, obstacles, dimensions);
        CollisionMove collisionMove = autoStep(current, actorMove, dimensions, canStep, maxUpStep, flying);
        Result result =
                finalizeMove(current, frame, dimensions, velocity, actorMove.requestedDelta(), collisionMove, shapes);
        BedrockActorOrderCrossing crossing = BedrockActorOrderCrossing.then(
                collisionMove.baseMove().actorOrder(),
                collisionMove.steppedUp() ? collisionMove.selectedMove().actorOrder() : null);
        if (solidActorOrder == 0) {
            if (crossing == null) return result;
            int axes = BedrockSolidActorOrders.decidedAxes(crossing.exactAxes());
            int order = axes == 0 ? 0 : BedrockSolidActorOrders.collected(axes, crossing.belowWon());
            return result.withSolidActorOrder(order, crossing.range(axes));
        }
        return result.withSolidActorOrder(
                solidActorOrder,
                crossing == null ? null : crossing.range(BedrockSolidActorOrders.axes(solidActorOrder)));
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
                false);
    }

    public static Result finalizeMove(
            BedrockMovementState current,
            BedrockInputFrame frame,
            PlayerDimensionsState dimensions,
            Vec3d velocity,
            Vec3d requestedDelta,
            CollisionMove collisionMove,
            List<BlockCollision> contactShapes) {
        FinalizedMove finalizedMove =
                finalizeMoveOutput(current, dimensions, velocity, requestedDelta, collisionMove, contactShapes);
        return verticalCollision(current, frame, collisionMove, finalizedMove, requestedDelta.y());
    }

    public static FinalizedMove finalizeMoveOutput(
            BedrockMovementState current,
            PlayerDimensionsState dimensions,
            Vec3d velocity,
            Vec3d requestedDelta,
            CollisionMove collisionMove,
            List<BlockCollision> contactShapes) {
        BedrockBlockCollisionResolver.Result blockCollision = BedrockBlockCollisionResolver.fromMove(
                requestedDelta,
                velocity,
                contactShapes,
                dimensions,
                collisionMove.baseMove(),
                collisionMove.selectedMove(),
                collisionMove.steppedUp());
        Vec3d nextPosition = blockCollision.position();
        Vec3d nextVelocity = blockCollision.velocity();
        BedrockCollisionFlags collisionFlags = finalizedCollisionFlags(current, requestedDelta, blockCollision, false);
        return new FinalizedMove(
                blockCollision,
                nextPosition,
                nextVelocity,
                collisionFlags,
                collisionFlags.onGround(),
                collisionMove.steppedUp(),
                collisionMove.stepRetryAllowed(),
                blockCollision.verticalCollision());
    }

    public static Result verticalCollision(
            BedrockMovementState current,
            BedrockInputFrame frame,
            CollisionMove collisionMove,
            FinalizedMove finalizedMove,
            double fallingVelocityY) {
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
                        false)
                .withBox(collisionMove.selectedMove().finalBox())
                .withPenetrating(collisionMove.baseMove().penetrating());
    }

    public static ActorMove actorMove(
            BedrockMovementState current,
            Vec3d requestedDelta,
            BlockCollisionWorld blockCollisionWorld,
            PlayerDimensionsState dimensions) {
        return actorMove(
                current, requestedDelta, BedrockCollisionSweep.collisionObstacles(blockCollisionWorld), dimensions);
    }

    static ActorMove actorMove(
            BedrockMovementState current,
            Vec3d requestedDelta,
            List<BlockCollision> obstacles,
            PlayerDimensionsState dimensions) {
        BedrockCollisionSweep.MoveResult baseMove = BedrockCollisionSweep.sweep(
                current.collisionBox(dimensions),
                requestedDelta,
                obstacles,
                current.coordinateFrame(),
                BedrockCollisionClipper.maximumDepenetration(current, obstacles));
        return new ActorMove(requestedDelta, obstacles, baseMove);
    }

    public static AutoStepRequest autoStepRequest(
            BedrockMovementState current, ActorMove actorMove, boolean canStep, double maxUpStep, boolean flying) {
        Vec3d requestedDelta = actorMove.requestedDelta();
        BedrockCollisionSweep.MoveResult baseMove = actorMove.baseMove();
        // A flying actor steps whether it rises, hovers or falls; otherwise it must stand or fall onto a floor.
        boolean verticalStateAllowsStep =
                flying || current.collisionFlags().onGround() || baseMove.yCollision() && requestedDelta.y() < 0.0D;
        boolean horizontalMovementChanged = horizontalMovementChanged(requestedDelta, baseMove);
        boolean stepRetryAllowed = maxUpStep > 0.0D && verticalStateAllowsStep && horizontalMovementChanged;
        return new AutoStepRequest(canStep && stepRetryAllowed, stepRetryAllowed);
    }

    public static CollisionMove autoStep(
            BedrockMovementState current,
            ActorMove actorMove,
            PlayerDimensionsState dimensions,
            AutoStepRequest autoStepRequest,
            double maxUpStep) {
        Vec3d requestedDelta = actorMove.requestedDelta();
        BedrockCollisionSweep.MoveResult baseMove = actorMove.baseMove();
        BedrockCollisionSweep.MoveResult selectedMove = baseMove;
        Optional<BedrockCollisionSweep.MoveResult> stepMove = Optional.empty();
        if (autoStepRequest.requested()) {
            stepMove = BedrockAutoStepResolver.resolve(
                    current, requestedDelta, baseMove, actorMove.obstacles(), dimensions, maxUpStep);
        }
        boolean steppedUp = false;
        if (autoStepRequest.requested() && stepMove.isPresent()) {
            selectedMove = stepMove.get();
            steppedUp = true;
        }
        return new CollisionMove(
                actorMove.obstacles(), baseMove, selectedMove, steppedUp, autoStepRequest.stepRetryAllowed());
    }

    public static CollisionMove autoStep(
            BedrockMovementState current,
            ActorMove actorMove,
            PlayerDimensionsState dimensions,
            boolean canStep,
            double maxUpStep,
            boolean flying) {
        AutoStepRequest autoStepRequest = autoStepRequest(current, actorMove, canStep, maxUpStep, flying);
        return autoStep(current, actorMove, dimensions, autoStepRequest, maxUpStep);
    }

    public static CollisionMove collide(
            BedrockMovementState current,
            Vec3d requestedDelta,
            BlockCollisionWorld blockCollisionWorld,
            PlayerDimensionsState dimensions,
            boolean canStep,
            double maxUpStep,
            boolean flying) {
        return collide(
                current,
                requestedDelta,
                BedrockCollisionSweep.collisionObstacles(blockCollisionWorld),
                dimensions,
                canStep,
                maxUpStep,
                flying);
    }

    static CollisionMove collide(
            BedrockMovementState current,
            Vec3d requestedDelta,
            List<BlockCollision> obstacles,
            PlayerDimensionsState dimensions,
            boolean canStep,
            double maxUpStep,
            boolean flying) {
        ActorMove actorMove = actorMove(current, requestedDelta, obstacles, dimensions);
        return autoStep(current, actorMove, dimensions, canStep, maxUpStep, flying);
    }

    private static boolean horizontalMovementChanged(Vec3d requestedDelta, BedrockCollisionSweep.MoveResult move) {
        double requestedX = BedrockCollisionSweep.f(requestedDelta.x());
        double requestedZ = BedrockCollisionSweep.f(requestedDelta.z());
        return Math.abs(requestedX - move.appliedDelta().x()) > EPSILON
                || Math.abs(requestedZ - move.appliedDelta().z()) > EPSILON;
    }

    public static BedrockCollisionFlags finalizedCollisionFlags(
            BedrockMovementState previous,
            Vec3d requestedDelta,
            BedrockBlockCollisionResolver.Result collision,
            boolean liquidClimbOut) {
        return new BedrockCollisionFlags(
                collision.onGround() || preservesGround(previous, requestedDelta, collision.verticalCollision()),
                collision.horizontalCollisionFlag(),
                collision.verticalCollision(),
                collision.horizontalBlockContact(),
                liquidClimbOut,
                collision.onGround(),
                collision.xCollision(),
                collision.zCollision());
    }

    public static boolean preservesGround(
            BedrockMovementState previous, Vec3d requestedDelta, boolean verticalCollision) {
        return previous.movementGrounded()
                && !verticalCollision
                && Math.abs(requestedDelta.y()) <= BedrockCollisionSweep.EPSILON;
    }

    public record ActorMove(
            Vec3d requestedDelta, List<BlockCollision> obstacles, BedrockCollisionSweep.MoveResult baseMove) {}

    public record AutoStepRequest(boolean requested, boolean stepRetryAllowed) {}

    public record CollisionMove(
            List<BlockCollision> obstacles,
            BedrockCollisionSweep.MoveResult baseMove,
            BedrockCollisionSweep.MoveResult selectedMove,
            boolean steppedUp,
            boolean stepRetryAllowed) {}

    public record FinalizedMove(
            BedrockBlockCollisionResolver.Result blockCollision,
            Vec3d position,
            Vec3d velocity,
            BedrockCollisionFlags collisionFlags,
            boolean onGround,
            boolean steppedUp,
            boolean stepRetryAllowed,
            boolean verticalCollision) {}

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
            boolean standingBounceBounced,
            WorldCollisionBox collisionBox,
            boolean penetrating,
            int solidActorOrder,
            BedrockActorOrderRange solidActorOrderRange) {
        public Result(
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
                boolean standingBounceBounced,
                WorldCollisionBox collisionBox,
                boolean penetrating) {
            this(
                    position,
                    velocity,
                    xCollision,
                    zCollision,
                    horizontalCollisionFlag,
                    horizontalBlockContact,
                    collisionFlags,
                    onGround,
                    steppedUp,
                    stepRetryAllowed,
                    verticalCollision,
                    standingBounceBounced,
                    collisionBox,
                    penetrating,
                    0,
                    null);
        }

        public Result(
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
                boolean standingBounceBounced,
                WorldCollisionBox collisionBox) {
            this(
                    position,
                    velocity,
                    xCollision,
                    zCollision,
                    horizontalCollisionFlag,
                    horizontalBlockContact,
                    collisionFlags,
                    onGround,
                    steppedUp,
                    stepRetryAllowed,
                    verticalCollision,
                    standingBounceBounced,
                    collisionBox,
                    false);
        }

        public Result(
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
                boolean standingBounceBounced) {
            this(
                    position,
                    velocity,
                    xCollision,
                    zCollision,
                    horizontalCollisionFlag,
                    horizontalBlockContact,
                    collisionFlags,
                    onGround,
                    steppedUp,
                    stepRetryAllowed,
                    verticalCollision,
                    standingBounceBounced,
                    null);
        }

        public Result withBox(WorldCollisionBox box) {
            return new Result(
                    position,
                    velocity,
                    xCollision,
                    zCollision,
                    horizontalCollisionFlag,
                    horizontalBlockContact,
                    collisionFlags,
                    onGround,
                    steppedUp,
                    stepRetryAllowed,
                    verticalCollision,
                    standingBounceBounced,
                    box,
                    penetrating,
                    solidActorOrder,
                    solidActorOrderRange);
        }

        /** Whether the move's own swept move overlapped any shape it met, which the next move's limit depends on. */
        public Result withPenetrating(boolean value) {
            return new Result(
                    position,
                    velocity,
                    xCollision,
                    zCollision,
                    horizontalCollisionFlag,
                    horizontalBlockContact,
                    collisionFlags,
                    onGround,
                    steppedUp,
                    stepRetryAllowed,
                    verticalCollision,
                    standingBounceBounced,
                    collisionBox,
                    value,
                    solidActorOrder,
                    solidActorOrderRange);
        }

        /** The order the move met its solid actors in and the range that order gives its end. */
        public Result withSolidActorOrder(int order, BedrockActorOrderRange range) {
            return new Result(
                    position,
                    velocity,
                    xCollision,
                    zCollision,
                    horizontalCollisionFlag,
                    horizontalBlockContact,
                    collisionFlags,
                    onGround,
                    steppedUp,
                    stepRetryAllowed,
                    verticalCollision,
                    standingBounceBounced,
                    collisionBox,
                    penetrating,
                    order,
                    range);
        }
    }
}
