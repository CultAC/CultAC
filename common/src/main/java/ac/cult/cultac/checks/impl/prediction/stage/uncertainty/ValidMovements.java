package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import static ac.cult.cultac.utils.math.VectorUtils.LARGE_MOVEMENT;

import ac.cult.cultac.bedrock.prediction.integration.BedrockMovementEngine;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.UncertaintyPipeline;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

// There are many things that can affect a user's movement
// They range from blocks pushing the user out of them, to pistons pushing a user
@ToString
public class ValidMovements {
    /** Collision data for the collision-ignored pass, shared by every player; writes to it throw. */
    private static final CollideAxisData COLLISION_IGNORED = CollideAxisData.immutable(
            new CollideAxisData.CollideResult(false, 0), null, null, new CollideAxisData.CollideResult(false, 0));

    private static final UncertaintyHandler[] MODIFIERS =
            UncertaintyPipeline.MODIFIERS.toArray(new UncertaintyHandler[0]);

    private static final boolean[] MODIFIER_POSITION_ONLY = positionOnly(MODIFIERS);

    private static final int LAST_MODIFIER = MODIFIERS.length - 1;

    // handleMovementTrace reads no probe state, so one instance serves every player.
    private static final CollisionModifier SELECTED_MOVEMENT_COLLISIONS = new CollisionModifier();

    static {
        // ElytraTransform's skip holds only for the start trace, which only the first handler sees.
        if (!(MODIFIERS[0] instanceof ElytraTransform)) {
            throw new IllegalStateException("ElytraTransform must run first in UncertaintyPipeline.MODIFIERS");
        }
        if (MODIFIERS.length > Long.SIZE) {
            throw new IllegalStateException("computeAABB keeps UncertaintyPipeline.MODIFIERS in a long mask");
        }
    }

    PredVector initialStartingVel;
    transient CultPlayer player;

    @Getter
    PredictionResult result;

    PredictionResult lastResult;

    @Getter
    @Setter
    SimpleCollisionBox collisionIgnoredMaxStartingVelExtents = null;

    @Getter
    @Setter
    Vec3 closestToTarget = null;

    @Getter
    MovementTrace closestTrace = null;

    @Getter
    @Setter
    boolean canStep;

    @Getter
    @Setter
    boolean controlsVerticalMovement;

    @Getter
    @Setter
    boolean didTickSkipGravity;

    private final List<UncertaintyHandler> uncertaintyHandlers;

    @ToString.Exclude
    private final UncertaintyHandler[] handlers;

    /** Per handler: it keeps {@link UncertaintyHandler}'s handleMovementTrace, which reads only the position. */
    @ToString.Exclude
    private final boolean[] positionOnly;

    @ToString.Exclude
    private final MovementTrace startTrace;

    /** The last {@link #positionTowards} run's trace; read right after the run returns, then cleared. */
    @ToString.Exclude
    private MovementTrace runTrace;

    @ToString.Exclude
    private boolean runMoved;

    public ValidMovements(PredVector initialStartingVel, CultPlayer player, PredictionResult result, boolean canStep) {
        this(initialStartingVel, player, result, canStep, UncertaintyPipeline.MODIFIERS);
    }

    public ValidMovements(
            PredVector initialStartingVel,
            CultPlayer player,
            PredictionResult result,
            boolean canStep,
            List<UncertaintyHandler> uncertaintyHandlers) {
        this.initialStartingVel = initialStartingVel;
        this.player = player;
        this.result = result;
        this.canStep = canStep;
        this.uncertaintyHandlers = uncertaintyHandlers;
        if (uncertaintyHandlers == UncertaintyPipeline.MODIFIERS) {
            this.handlers = MODIFIERS;
            this.positionOnly = MODIFIER_POSITION_ONLY;
        } else if (uncertaintyHandlers == BedrockMovementEngine.UNCERTAINTY_HANDLERS) {
            this.handlers = BedrockHandlers.HANDLERS;
            this.positionOnly = BedrockHandlers.POSITION_ONLY;
        } else {
            this.handlers = uncertaintyHandlers.toArray(new UncertaintyHandler[0]);
            this.positionOnly = positionOnly(this.handlers);
        }
        this.startTrace = MovementTrace.start(initialStartingVel);

        result.setValidMovements(this);
    }

    public Vec3 transformTowards(Vec3 end, PredictionResult lastResult) {
        return transformTraceTowards(end, lastResult).position();
    }

    public MovementTrace transformTraceTowards(Vec3 end, PredictionResult lastResult) {
        return transformTraceTowards(-1L, handlers.length, end, lastResult);
    }

    private MovementTrace transformTraceTowards(long kept, int count, Vec3 end, PredictionResult lastResult) {
        PredVector position = positionTowards(kept, count, end, lastResult);
        MovementTrace trace = runTrace;
        runTrace = null;
        return runMoved ? movedTo(trace, position) : trace;
    }

    /**
     * Runs the first {@code count} handlers whose bit {@code kept} sets and returns the final position. A run of
     * handlers that read only the position passes the position along; the trace the chain of
     * {@link MovementTrace#withPosition} calls would return is {@link #runTrace} moved to that position when
     * {@link #runMoved}, else {@link #runTrace} itself.
     */
    private PredVector positionTowards(long kept, int count, Vec3 end, PredictionResult lastResult) {
        MovementTrace trace = startTrace;
        PredVector position = trace.position();
        boolean moved = false;
        SimulationContext context = result.getSimulationContext();
        Vec3 target =
                isTestingMaxStartingVelExtents(end) ? end : Riptide.movementTarget(context, initialStartingVel, end);

        for (int i = 0; i < count; i++) {
            if ((kept & (1L << i)) == 0) continue;
            UncertaintyHandler handler = handlers[i];
            if (handler instanceof Riptide) target = end;
            if (positionOnly[i]) {
                PredVector next = handler.handleUncertainty(
                        player, result.getValidMovements(), result, context, lastResult, position, target);
                moved |= next != position;
                position = next;
                continue;
            }
            if (moved) {
                trace = movedTo(trace, position);
                moved = false;
            }
            trace = handler.handleMovementTrace(
                    player, result.getValidMovements(), result, context, lastResult, trace, target);
            position = trace.position();
        }

        runTrace = trace;
        runMoved = moved;
        return position;
    }

    private static MovementTrace movedTo(MovementTrace trace, PredVector position) {
        return new MovementTrace(
                position,
                trace.positionOnlyDelta(),
                trace.collisionBaseOffset(),
                trace.preCollisionVelocity(),
                trace.preCollisionMovement(),
                trace.shulkerBoxShove());
    }

    private static boolean[] positionOnly(UncertaintyHandler[] handlers) {
        boolean[] positionOnly = new boolean[handlers.length];
        for (int i = 0; i < handlers.length; i++) {
            try {
                positionOnly[i] = handlers[i]
                                .getClass()
                                .getMethod(
                                        "handleMovementTrace",
                                        CultPlayer.class,
                                        ValidMovements.class,
                                        PredictionResult.class,
                                        SimulationContext.class,
                                        PredictionResult.class,
                                        MovementTrace.class,
                                        Vec3.class)
                                .getDeclaringClass()
                        == UncertaintyHandler.class;
            } catch (NoSuchMethodException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        return positionOnly;
    }

    public ValidMovements computeExtents(PredictionResult lastResult) {
        // closestToTarget will call the AABB, so just figure it out now. Also cache it, to prevent infinite recursion
        // to the previous movement.
        SimpleCollisionBox vectorExtents = initialStartingVel.collisionIgnoredExtents(result.getTarget());
        this.collisionIgnoredMaxStartingVelExtents = vectorExtents == null ? computeAABB(lastResult) : vectorExtents;
        return this;
    }

    public ValidMovements computeClosest(PredictionResult lastResult) {
        this.closestTrace = transformTraceTowards(result.getTarget(), lastResult);
        this.closestToTarget = closestTrace.position();
        return this;
    }

    public Vec3 computeCollisionIgnoredMovement(PredictionResult lastResult) {
        return computeCollisionIgnoredMovement(result.getTarget(), lastResult);
    }

    /** Selects one complete movement towards {@code end} with collisions ignored. */
    public Vec3 computeCollisionIgnoredMovement(Vec3 end, PredictionResult lastResult) {
        // Select one complete movement with the existing uncertainty handlers.
        result.setCollideAxisData(COLLISION_IGNORED);
        setClosestTrace(transformTraceTowards(end, lastResult));
        return closestToTarget;
    }

    /**
     * The target with each horizontal axis the current collisions clip replaced by the starting velocity's value
     * there, when the target sits on the clip, the starting value is already past it, and a circular allowance in
     * the selected movement moved that axis. Entity#collide clips every attempt past an obstacle to the same value,
     * so the target's value on such an axis does not bound the attempt. Returns the target itself when no axis
     * qualifies.
     */
    public Vec3 targetPastClippedAxes() {
        Vec3 target = result.getTarget();
        CollideAxisData collisions = result.getCollideAxisData();
        double x = pastClip(collisions.getX(), target.x, true);
        double z = pastClip(collisions.getZ(), target.z, false);
        return x == target.x && z == target.z ? target : new Vec3(x, target.y, z);
    }

    private double pastClip(CollideAxisData.CollideResult clip, double target, boolean xAxis) {
        if (clip == null || !clip.isLikelyCollide()) return target;
        double limit = clip.getResult();
        if (Math.abs(target - limit) > 1.0E-7D) return target;
        double start = xAxis ? initialStartingVel.x : initialStartingVel.z;
        boolean past = limit > 0.0D ? start >= limit : limit < 0.0D && start <= limit;
        // UncertaintyHelper#handleSpherical moves X/Z through handleCircular.
        return past && closestTrace.position().reasonMovedAxisSince("circular", initialStartingVel, xAxis)
                ? start
                : target;
    }

    /**
     * Restores {@code previous}, already clipped by {@code previousCollisions}, unless the selected movement ends
     * nearer the target.
     */
    public void keepNearerToTarget(MovementTrace previous, CollideAxisData previousCollisions) {
        Vec3 target = result.getTarget();
        if (closestToTarget.distanceToSqr(target) < previous.position().distanceToSqr(target)) return;
        result.setCollideAxisData(previousCollisions);
        setClosestTrace(previous);
    }

    public void applyCollisionsToSelectedMovement(PredictionResult lastResult) {
        // Reuse the selected movement: running the uncertainty handlers again
        // after clipping would grant their allowance a second time.
        MovementTrace movement = closestTrace;
        Vec3 base = movement.collisionBaseOffset();
        if (base.lengthSqr() > 1.0E-14) {
            movement = movement.withPosition(movement.position()
                    .with(movement.position().subtract(base), "external movement collision base removed"));
        }
        setClosestTrace(SELECTED_MOVEMENT_COLLISIONS.handleMovementTrace(
                player, this, result, result.getSimulationContext(), lastResult, movement, result.getTarget()));
    }

    public Vec3 getPositionOnlyDelta() {
        return closestTrace == null ? Vec3.ZERO : closestTrace.positionOnlyDelta();
    }

    protected void setClosestTrace(MovementTrace closestTrace) {
        this.closestTrace = closestTrace;
        this.closestToTarget = closestTrace == null ? null : closestTrace.position();
    }

    protected PredVector initialStartingVelocity() {
        return initialStartingVel;
    }

    public SimpleCollisionBox computeAABB(PredictionResult lastResult) {
        // Only handlers run between the six traces, and no skip predicate reads what a handler writes.
        long kept = -1L;
        int count = handlers.length;
        PointThreeTesting last = null;
        if (handlers == MODIFIERS) {
            kept = 0L;
            SimulationContext context = result.getSimulationContext();
            for (int i = 0; i < count; i++) {
                if (!handlers[i].skipsContext(player, context, lastResult)) kept |= 1L << i;
            }
            // PointThreeTesting runs last and has no side effect, and only one coordinate of its position is read.
            if (handlers[LAST_MODIFIER].getClass() == PointThreeTesting.class && (kept & (1L << LAST_MODIFIER)) != 0) {
                last = (PointThreeTesting) handlers[LAST_MODIFIER];
                count = LAST_MODIFIER;
            }
        }
        double posX = extent(kept, count, last, initialStartingVel.withX(LARGE_MOVEMENT), lastResult, 0);
        double negX = extent(kept, count, last, initialStartingVel.withX(-LARGE_MOVEMENT), lastResult, 0);
        double posY = extent(kept, count, last, initialStartingVel.withY(LARGE_MOVEMENT), lastResult, 1);
        double negY = extent(kept, count, last, initialStartingVel.withY(-LARGE_MOVEMENT), lastResult, 1);
        double posZ = extent(kept, count, last, initialStartingVel.withZ(LARGE_MOVEMENT), lastResult, 2);
        double negZ = extent(kept, count, last, initialStartingVel.withZ(-LARGE_MOVEMENT), lastResult, 2);
        runTrace = null;

        return new SimpleCollisionBox(negX, negY, negZ, posX, posY, posZ).sort();
    }

    /** Coordinate {@code axis} (0 x, 1 y, 2 z) of the kept handlers' position, then {@code last}'s, towards end. */
    private double extent(
            long kept, int count, PointThreeTesting last, Vec3 end, PredictionResult lastResult, int axis) {
        PredVector position = positionTowards(kept, count, end, lastResult);
        if (last != null) return last.coordinate(player, result.getSimulationContext(), position, end, axis);
        return axis == 0 ? position.x : axis == 1 ? position.y : position.z;
    }

    public boolean isTestingMaxStartingVelExtents(Vec3 end) {
        return Math.abs(end.x) == LARGE_MOVEMENT
                || Math.abs(end.y) == LARGE_MOVEMENT
                || Math.abs(end.z) == LARGE_MOVEMENT;
    }

    public void addTransformers(UncertaintyPipeline uncertaintyPipeline) {}

    /** Initialized on the first non-pipeline handler list, so Java-only use never initializes the Bedrock engine. */
    private static final class BedrockHandlers {
        private static final UncertaintyHandler[] HANDLERS =
                BedrockMovementEngine.UNCERTAINTY_HANDLERS.toArray(new UncertaintyHandler[0]);
        private static final boolean[] POSITION_ONLY = positionOnly(HANDLERS);
    }
}
