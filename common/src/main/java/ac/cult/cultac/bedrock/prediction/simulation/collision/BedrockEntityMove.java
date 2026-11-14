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
                solidActorOrder,
                null);
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
            boolean flying,
            WorldCollisionBox fetchBox,
            int solidActorOrder,
            Vec3d nominalEnd) {
        if (blockCollisionWorld.isEmpty()) {
            WorldCollisionBox startBox = current.collisionBox(dimensions);
            Vec3d delta = requestedPosition.subtract(current.physicalFeetPosition());
            WorldCollisionBox box = BedrockActorBox.move(startBox, delta, current.coordinateFrame());
            return noCollisionResult(BedrockActorBox.feet(box, current.coordinateFrame()), velocity)
                    .withBox(box)
                    .withPreCastPosition(preCastPosition(startBox, floatMove(delta), current));
        }

        Vec3d requestedDelta = requestedPosition.subtract(current.physicalFeetPosition());
        List<BlockCollision> shapes = BedrockCollisionSweep.collisionObstacles(blockCollisionWorld);
        WorldCollisionBox start = current.collisionBox(dimensions);
        // Only solid actors raise the limit, and the move fetches every one of them wherever it stands.
        BedrockDepenetrationLimit collectedLimit = BedrockCollisionClipper.maximumDepenetration(current, shapes);
        var placement = BedrockSolidActorPositions.place(
                shapes,
                start,
                requestedDelta,
                nominalEnd == null ? requestedDelta : nominalEnd,
                current.coordinateFrame(),
                BedrockSolidActorOrders.positionChanges(solidActorOrder),
                BedrockSolidActorOrders.nominalContacts(solidActorOrder),
                BedrockSolidActorOrders.overlapChanges(solidActorOrder),
                false,
                collectedLimit);
        Placed placed = null;
        int steppedOver = 0;
        if (placement != null && placement.onset() != 0 && canStep) {
            // An actor brought into the move's way stops it only where it cannot be stepped onto. The same actor a
            // little nearer is one the move steps onto, wherever in its way the client shows it.
            var within = BedrockSolidActorPositions.place(
                    shapes,
                    start,
                    requestedDelta,
                    nominalEnd == null ? requestedDelta : nominalEnd,
                    current.coordinateFrame(),
                    BedrockSolidActorOrders.positionChanges(solidActorOrder),
                    BedrockSolidActorOrders.nominalContacts(solidActorOrder),
                    BedrockSolidActorOrders.overlapChanges(solidActorOrder),
                    true,
                    collectedLimit);
            if (within != null && within.onset() == placement.onset()) {
                Placed over = sweepPlaced(
                        current,
                        frame,
                        dimensions,
                        velocity,
                        requestedDelta,
                        within.shapes(),
                        fetchBox,
                        solidActorOrder,
                        canStep,
                        maxUpStep,
                        flying,
                        start);
                if (stepsOver(over, within.onset(), requestedDelta, nominalEnd, current)) {
                    placement = within;
                    placed = over;
                    steppedOver = within.onset();
                }
            }
        }
        if (placed == null) {
            placed = sweepPlaced(
                    current,
                    frame,
                    dimensions,
                    velocity,
                    requestedDelta,
                    placement == null ? shapes : placement.shapes(),
                    fetchBox,
                    solidActorOrder,
                    canStep,
                    maxUpStep,
                    flying,
                    start);
        }
        Result result = placed.result();
        CollisionMove collisionMove = placed.move();
        BedrockActorOrderCrossing crossing = BedrockActorOrderCrossing.then(
                collisionMove.baseMove().actorOrder(),
                collisionMove.steppedUp() ? collisionMove.selectedMove().actorOrder() : null);
        if (solidActorOrder == 0) {
            int axes = crossing == null ? 0 : BedrockSolidActorOrders.decidedAxes(crossing.exactAxes());
            int order = axes == 0 ? 0 : BedrockSolidActorOrders.collected(axes, crossing.belowWon());
            var range = crossing == null ? null : crossing.range(axes);
            int contacts = range == null ? 0 : range.bufferedAxes();
            int positionAxes = BedrockSolidActorPositions.axes(
                    shapes,
                    start,
                    requestedDelta,
                    result.position().subtract(current.physicalFeetPosition()),
                    contacts,
                    current.coordinateFrame(),
                    collectedLimit);
            order = BedrockSolidActorOrders.withPositionStates(order, positionAxes, contacts);
            if (collisionMove.steppedUp()) {
                int overlapAxes = BedrockSolidActorPositions.overlapAxes(
                        shapes,
                        start,
                        requestedDelta,
                        contacts,
                        collided(result),
                        current.coordinateFrame(),
                        collectedLimit);
                order = BedrockSolidActorOrders.withOverlapStates(order, overlapAxes, contacts);
            }
            return result.withSolidActorOrder(order, range);
        }
        var range = crossing == null ? null : crossing.range(BedrockSolidActorOrders.axes(solidActorOrder));
        if (placement != null) {
            int contacts = collided(result);
            int actorContacts = range == null ? 0 : range.bufferedAxes();
            int nominalContacts = BedrockSolidActorOrders.nominalContacts(solidActorOrder);
            int realized = (~nominalContacts & actorContacts & contacts) | (nominalContacts & ~contacts);
            solidActorOrder =
                    BedrockSolidActorOrders.withRealizedPositions(solidActorOrder, realized & placement.changed());
            if (steppedOver != 0) {
                solidActorOrder = BedrockSolidActorOrders.withSteppedPositions(solidActorOrder, steppedOver);
                placement = placement.steppedOver(steppedOver);
            }
            if (BedrockSolidActorOrders.realizesPositionState(solidActorOrder, contacts))
                range = placement.range(range);
        }
        return result.withSolidActorOrder(solidActorOrder, range);
    }

    /** The move through {@code collisionShapes}, met in {@code solidActorOrder}, with its step. */
    private static Placed sweepPlaced(
            BedrockMovementState current,
            BedrockInputFrame frame,
            PlayerDimensionsState dimensions,
            Vec3d velocity,
            Vec3d requestedDelta,
            List<BlockCollision> collisionShapes,
            WorldCollisionBox fetchBox,
            int solidActorOrder,
            boolean canStep,
            double maxUpStep,
            boolean flying,
            WorldCollisionBox start) {
        List<BlockCollision> obstacles = BedrockCollisionFetchBox.fetched(collisionShapes, fetchBox);
        if (solidActorOrder != 0) {
            obstacles = BedrockSolidActorOrders.arrange(
                    obstacles,
                    solidActorOrder,
                    start,
                    current.coordinateFrame(),
                    BedrockCollisionClipper.maximumDepenetration(current, obstacles));
        }
        ActorMove actorMove = actorMove(current, requestedDelta, obstacles, dimensions);
        CollisionMove collisionMove = autoStep(current, actorMove, dimensions, canStep, maxUpStep, flying);
        Result result = finalizeMove(
                        current,
                        frame,
                        dimensions,
                        velocity,
                        actorMove.requestedDelta(),
                        collisionMove,
                        collisionShapes)
                .withPreCastPosition(
                        preCastPosition(start, collisionMove.selectedMove().appliedDelta(), current));
        return new Placed(result, collisionMove);
    }

    private record Placed(Result result, CollisionMove move) {}

    /** The axes the move ends collided along (X 1, Y 2, Z 4). */
    private static int collided(Result result) {
        return (result.xCollision() ? 1 : 0) | (result.verticalCollision() ? 2 : 0) | (result.zCollision() ? 4 : 0);
    }

    /**
     * Whether a step carried the move over the actors brought into its way along {@code axes}: the sweep stopped at
     * each, the step did not, and the move ends somewhere other than {@code nominalEnd}, where it ends without them.
     */
    private static boolean stepsOver(
            Placed placed, int axes, Vec3d requestedDelta, Vec3d nominalEnd, BedrockMovementState current) {
        if (!placed.move().steppedUp() || (collided(placed.result()) & axes) != 0) return false;
        Vec3d swept = placed.move().baseMove().appliedDelta();
        if ((axes & 1) != 0 && swept.x() == BedrockCollisionSweep.f(requestedDelta.x())) return false;
        if ((axes & 2) != 0 && swept.y() == BedrockCollisionSweep.f(requestedDelta.y())) return false;
        if ((axes & 4) != 0 && swept.z() == BedrockCollisionSweep.f(requestedDelta.z())) return false;
        return nominalEnd == null
                || !placed.result()
                        .position()
                        .subtract(current.physicalFeetPosition())
                        .equals(nominalEnd);
    }

    private static Vec3d preCastPosition(WorldCollisionBox start, Vec3d appliedMove, BedrockMovementState current) {
        return BedrockActorBox.feet(start, current.coordinateFrame()).add(appliedMove);
    }

    private static Vec3d floatMove(Vec3d move) {
        return new Vec3d((float) move.x(), (float) move.y(), (float) move.z());
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
            BedrockActorOrderRange solidActorOrderRange,
            Vec3d preCastPosition) {
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
                boolean penetrating,
                int solidActorOrder,
                BedrockActorOrderRange solidActorOrderRange) {
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
                    solidActorOrder,
                    solidActorOrderRange,
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
                    solidActorOrderRange,
                    preCastPosition);
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
                    solidActorOrderRange,
                    preCastPosition);
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
                    range,
                    preCastPosition);
        }

        /** Where the start box's feet end after the final move, in double, before the box rounds them to float. */
        public Result withPreCastPosition(Vec3d value) {
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
                    solidActorOrder,
                    solidActorOrderRange,
                    value);
        }
    }
}
