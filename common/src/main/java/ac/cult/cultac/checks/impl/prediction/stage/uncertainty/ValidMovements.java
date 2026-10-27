package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import static ac.cult.cultac.utils.math.VectorUtils.LARGE_MOVEMENT;

import ac.cult.cultac.bedrock.prediction.integration.BedrockMovementEngine;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaForwardMove;
import ac.cult.cultac.checks.impl.prediction.stage.UncertaintyPipeline;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
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

    static {
        // ElytraTransform's skip holds only for the start trace, which only the first handler sees.
        if (!(MODIFIERS[0] instanceof ElytraTransform)) {
            throw new IllegalStateException("ElytraTransform must run first in UncertaintyPipeline.MODIFIERS");
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

    private final List<UncertaintyHandler> uncertaintyHandlers;

    @ToString.Exclude
    private final UncertaintyHandler[] handlers;

    @ToString.Exclude
    private final MovementTrace startTrace;

    /** Carry-only replay of water jump/sneak and swimming, whose input a vertical collision can hide. */
    private int waterInputChoice = -1;

    private boolean waterInputsReplayed;
    private boolean selectedWaterInputExact;

    /** An independently generated keyboard input whose travel was already collided by the late-pass replay. */
    @Getter
    private Vec3 replayedTravelInput;

    @Getter
    private Boolean replayedClimbing;

    @Getter
    private Vec3 replayedTravelMovement;

    public static void useTravelReplay(
            PredictionResult result, MovementTrace trace, Vec3 input, boolean climbing, Vec3 movement) {
        var valid = new ValidMovements(result.getInitialStartingVel(), result.getPlayer(), result, false, List.of());
        valid.replayedTravelInput = input;
        valid.replayedClimbing = climbing;
        valid.replayedTravelMovement = movement;
        valid.setClosestTrace(trace);
    }

    public boolean hasPartialWaterInput() {
        return waterInputsReplayed && !selectedWaterInputExact;
    }

    public boolean isWaterInputCandidate() {
        return waterInputChoice >= 0;
    }

    // Freeze the withheld-position allowance used during prediction for carry derivation.
    private double reportRadius;

    public void noteReportRadius(double radius) {
        reportRadius = Math.max(reportRadius, radius);
    }

    public ValidMovements(PredVector initialStartingVel, CultPlayer player, PredictionResult result, boolean canStep) {
        this(initialStartingVel, player, result, canStep, UncertaintyPipeline.MODIFIERS);
    }

    /**
     * The zero impulse already included in EntityPush's allowance. A late concrete shove can supply the report's
     * displacement by itself; selecting an impulse toward that final report first would count the shove twice.
     */
    public ValidMovements withoutEntityPush(PredictionResult prediction) {
        List<UncertaintyHandler> kept = uncertaintyHandlers.stream()
                .filter(handler -> !(handler instanceof EntityPush))
                .toList();
        return new ValidMovements(initialStartingVel, player, prediction, canStep, kept);
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
        } else if (uncertaintyHandlers == BedrockMovementEngine.UNCERTAINTY_HANDLERS) {
            this.handlers = BedrockHandlers.HANDLERS;
        } else {
            this.handlers = uncertaintyHandlers.toArray(new UncertaintyHandler[0]);
        }
        this.startTrace = MovementTrace.start(initialStartingVel);

        result.setValidMovements(this);
    }

    public Vec3 transformTowards(Vec3 end, PredictionResult lastResult) {
        return transformTraceTowards(end, lastResult).position();
    }

    public MovementTrace transformTraceTowards(Vec3 end, PredictionResult lastResult) {
        return transformTraceTowards(handlers, handlers.length, end, lastResult);
    }

    private MovementTrace transformTraceTowards(
            UncertaintyHandler[] handlers, int count, Vec3 end, PredictionResult lastResult) {
        MovementTrace trace = startTrace;
        SimulationContext context = result.getSimulationContext();
        Vec3 target = end;

        for (int i = 0; i < count; i++) {
            if (waterInputChoice >= 0) {
                int choice = handlers[i] instanceof SwimSpaceShift
                        ? waterInputChoice / 3
                        : handlers[i] instanceof AquaticUpdateSwim ? waterInputChoice % 3 : -1;
                if (choice == 0) continue;
                if (choice > 0) {
                    trace = handlers[i].handleMovementTrace(
                            player,
                            this,
                            result,
                            context,
                            lastResult,
                            trace,
                            new Vec3(target.x, choice == 1 ? LARGE_MOVEMENT : -LARGE_MOVEMENT, target.z));
                    continue;
                }
            }
            trace = handlers[i].handleMovementTrace(
                    player, result.getValidMovements(), result, context, lastResult, trace, target);
        }

        return pinBlockEntityAxes(context, trace);
    }

    /** A clipped shove or moving face fixes its axis independently of travel's horizontal input. */
    private static MovementTrace pinBlockEntityAxes(SimulationContext context, MovementTrace trace) {
        var pass = trace.passAfterTravel();
        if (pass == null || context.isWithheldPosition()) return trace;
        var fixed = pass.result();
        Vec3 scale = context.getLastStuckSpeed();
        Vec3 move =
                fixed.state().position().subtract(context.getStart()).multiply(1 / scale.x, 1 / scale.y, 1 / scale.z);
        return trace.withPosition(trace.position()
                .withXYZ(
                        fixed.fixedX() ? move.x : trace.position().x,
                        fixed.fixedY() ? move.y : trace.position().y,
                        fixed.fixedZ() ? move.z : trace.position().z,
                        "block entity fixed axes"));
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
     * Replaces the selected collision-ignored movement with its forward Entity#move and that move's exact collision.
     */
    public void applyForwardMove(PredictionResult lastResult) {
        JavaForwardMove.Result moved = JavaForwardMove.move(player, result, closestTrace, result.getTarget());
        result.setCollideAxisData(moved.collision());
        setClosestTrace(withheldReport(moved.trace(), result.getTarget()));
    }

    /**
     * LocalPlayer#sendPosition withholds the position only while it lies within 2.0E-4 of the last one sent. On such a
     * tick the report is that last position and the client is where it travelled, so the gap is position, never
     * deltaMovement. A travel ending farther away would have been sent: its whole gap stays.
     */
    private MovementTrace withheldReport(MovementTrace trace, Vec3 end) {
        SimulationContext context = result.getSimulationContext();
        if (player.isBedrockMovement() || !context.isWithheldPosition()) return trace;
        Vec3 stuck = context.getLastStuckSpeed();
        double threshold = player.getMovementThreshold();
        noteReportRadius(threshold * Math.max(context.getTargetScalarHoriz(), context.getTargetScalarVert()));
        Vec3 gap = end.subtract(trace.position());
        if (gap.multiply(stuck).lengthSqr() > threshold * threshold) return trace;
        return trace.withPosition(trace.position().withXYZOrSelf(end.x, end.y, end.z, "withheld position"))
                .addPositionOnlyDelta(gap.multiply(stuck));
    }

    public Vec3 getPositionOnlyDelta() {
        return closestTrace == null ? Vec3.ZERO : closestTrace.positionOnlyDelta();
    }

    /**
     * The report-position allowance around the move: LocalPlayer#sendPosition's withheld position is not
     * deltaMovement, so an alternative may leave that much of the move as position.
     */
    private SimpleCollisionBox reportPositionEnvelope() {
        SimpleCollisionBox envelope = new SimpleCollisionBox(0, 0, 0, 0, 0, 0);
        if (!player.isBedrockMovement() && reportRadius > 0) envelope.expand(reportRadius, 0, reportRadius);
        return envelope;
    }

    /**
     * LivingEntity#jumpInLiquid and Player#travel can leave different deltaMovement values behind the same floor or
     * ceiling clip. Replay those existing handlers with their input present/absent through JavaForwardMove.
     * Each complete alternative retains its own trace for restitution and block effects; never span the gap between
     * an unclipped falling velocity and a bounce. Each candidate keeps its own exact collision.
     */
    public List<PredictionResult> collisionVelocityAlternatives(PredictionResult lastResult) {
        var alternatives = new ArrayList<>(waterCollisionVelocityAlternatives(lastResult));
        SimulationContext context = result.getSimulationContext();
        if (waterInputChoice >= 0
                || player.isBedrockMovement()
                || context.getVehicle() != null
                || result.getHorizontalInputRadius() <= 0) return alternatives;
        Vec3 target = result.getTarget();
        Vec3 collisionTarget = target.subtract(closestTrace.collisionBaseOffset());
        CollideAxisData collisions = result.getCollideAxisData();
        boolean x = atClip(collisions.getX(), collisionTarget.x);
        boolean z = atClip(collisions.getZ(), collisionTarget.z);
        if (!x && !z) return alternatives;
        SimpleCollisionBox push = reportPositionEnvelope();
        // A wall can hide moveRelative input beyond the clipped move. Use the same forward resolver
        // with each possible input direction, so zeroed velocity and post-collision climb boosts are
        // carried from an actual Entity#move trace rather than from the clipped displacement.
        for (int dx = x ? -1 : 0; dx <= (x ? 1 : 0); dx++) {
            for (int dz = z ? -1 : 0; dz <= (z ? 1 : 0); dz++) {
                if (dx == 0 && dz == 0) continue;
                Vec3 inputTarget = target.add(dx * LARGE_MOVEMENT, 0, dz * LARGE_MOVEMENT);
                JavaForwardMove.Result moved = forwardTowards(inputTarget, lastResult);
                addCollisionAlternative(alternatives, moved.trace(), push, moved.collision(), -1);
            }
        }
        return alternatives;
    }

    private List<PredictionResult> waterCollisionVelocityAlternatives(PredictionResult lastResult) {
        SimulationContext context = result.getSimulationContext();
        if (waterInputChoice >= 0
                || player.isBedrockMovement()
                || context.getVehicle() != null
                || ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaTravelInputs.usesReportedKeys(
                        player, context)
                || context.usesFallFlyingMovement()
                || !context.getWorldData().isInLiquid()
                || collisionIgnoredMaxStartingVelExtents == null
                || collisionIgnoredMaxStartingVelExtents.minY == collisionIgnoredMaxStartingVelExtents.maxY)
            return List.of();
        Vec3 target = result.getTarget();
        Vec3 collisionTarget = target.subtract(closestTrace.collisionBaseOffset());
        CollideAxisData collisions = result.getCollideAxisData();
        // A report allowance can hide a partial jump/swim selection even without a collision.
        // Carry only the source on/off choices when those same probes complete the observed move.
        SimpleCollisionBox push = reportPositionEnvelope();
        var alternatives = new ArrayList<PredictionResult>();
        waterInputsReplayed = true;
        selectedWaterInputExact = false;
        int choices = context.isSneaking() ? 9 : 6;
        for (int choice = 0; choice < choices; choice++) {
            MovementTrace trace;
            CollideAxisData traceCollisions = collisions;
            waterInputChoice = choice;
            try {
                JavaForwardMove.Result moved = forwardTowards(target, lastResult);
                trace = moved.trace();
                traceCollisions = moved.collision();
            } finally {
                waterInputChoice = -1;
            }
            if (sameCarryTrace(trace, closestTrace)) selectedWaterInputExact = true;
            addCollisionAlternative(alternatives, trace, push, traceCollisions, choice);
        }
        return alternatives;
    }

    /** Replays one candidate through the same forward Entity#move used by the main prediction. */
    private JavaForwardMove.Result forwardTowards(Vec3 end, PredictionResult lastResult) {
        CollideAxisData collisions = result.getCollideAxisData();
        var lastOnGround = result.getSimulationContext().getWorldData().getLastOnGround();
        MovementTrace trace;
        try {
            result.setCollideAxisData(COLLISION_IGNORED);
            trace = transformTraceTowards(end, lastResult);
        } finally {
            result.setCollideAxisData(collisions);
            result.getSimulationContext().getWorldData().setLastOnGround(lastOnGround);
        }
        JavaForwardMove.Result moved = JavaForwardMove.move(player, result, trace, end);
        return new JavaForwardMove.Result(withheldReport(moved.trace(), end), moved.collision());
    }

    private void addCollisionAlternative(
            List<PredictionResult> alternatives,
            MovementTrace trace,
            SimpleCollisionBox push,
            CollideAxisData collisions,
            int choice) {
        SimulationContext context = result.getSimulationContext();
        Vec3 target = result.getTarget();
        Vec3 move = trace.position();
        if (!completes(move.x, target.x, push.minX, push.maxX)
                || !completes(move.y, target.y, push.minY, push.maxY)
                || !completes(move.z, target.z, push.minZ, push.maxZ)) return;
        if (sameCarryTrace(trace, closestTrace)) return;
        boolean duplicate = false;
        for (var other : alternatives) {
            if (sameCarryTrace(trace, other.getValidMovements().closestTrace)) {
                duplicate = true;
                break;
            }
        }
        if (duplicate) return;
        Vec3 shove = target.subtract(move);
        if (shove.lengthSqr() > 1.0E-14) {
            trace = new MovementTrace(
                    trace.position().add(shove, "withheld position"),
                    trace.positionOnlyDelta().add(shove.multiply(context.getLastStuckSpeed())),
                    trace.collisionBaseOffset().add(shove),
                    trace.preCollisionVelocity(),
                    trace.preCollisionMovement(),
                    trace.passAfterTravel());
        }
        var other = new PredictionResult(
                player,
                initialStartingVel,
                context,
                target,
                result.getSetBackData(),
                result.getRealities(),
                result.getAnyReality());
        other.setCollideAxisData(collisions);
        other.setSneakingInputMultiplier(result.getSneakingInputMultiplier());
        var valid = new ValidMovements(initialStartingVel, player, other, canStep, uncertaintyHandlers);
        valid.collisionIgnoredMaxStartingVelExtents = collisionIgnoredMaxStartingVelExtents;
        valid.waterInputChoice = choice;
        valid.reportRadius = reportRadius;
        valid.setClosestTrace(trace);
        alternatives.add(other);
    }

    private static boolean atClip(CollideAxisData.CollideResult clip, double y) {
        return clip != null && clip.isLikelyCollide() && Math.abs(clip.getResult() - y) <= 1.0E-7;
    }

    private static boolean sameCarryTrace(MovementTrace a, MovementTrace b) {
        return a.preCollisionVelocity().equals(b.preCollisionVelocity())
                && a.preCollisionMovement().equals(b.preCollisionMovement())
                && a.position()
                        .subtract(a.collisionBaseOffset())
                        .equals(b.position().subtract(b.collisionBaseOffset()));
    }

    /** Whether a push in {@code [min, max]} moves {@code velocity} to {@code target} on one axis. */
    private static boolean completes(double velocity, double target, double min, double max) {
        double push = target - velocity;
        return push >= min - SimpleCollisionBox.COLLISION_EPSILON && push <= max + SimpleCollisionBox.COLLISION_EPSILON;
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
        UncertaintyHandler[] kept = handlers;
        int count = kept.length;
        if (handlers == MODIFIERS) {
            kept = new UncertaintyHandler[handlers.length];
            count = 0;
            SimulationContext context = result.getSimulationContext();
            for (UncertaintyHandler handler : handlers) {
                if (!handler.skipsContext(player, context, lastResult)) kept[count++] = handler;
            }
        }
        double posX = transformTraceTowards(kept, count, initialStartingVel.withX(LARGE_MOVEMENT), lastResult)
                .position()
                .x;
        double negX = transformTraceTowards(kept, count, initialStartingVel.withX(-LARGE_MOVEMENT), lastResult)
                .position()
                .x;
        double posY = transformTraceTowards(kept, count, initialStartingVel.withY(LARGE_MOVEMENT), lastResult)
                .position()
                .y;
        double negY = transformTraceTowards(kept, count, initialStartingVel.withY(-LARGE_MOVEMENT), lastResult)
                .position()
                .y;
        double posZ = transformTraceTowards(kept, count, initialStartingVel.withZ(LARGE_MOVEMENT), lastResult)
                .position()
                .z;
        double negZ = transformTraceTowards(kept, count, initialStartingVel.withZ(-LARGE_MOVEMENT), lastResult)
                .position()
                .z;

        return new SimpleCollisionBox(negX, negY, negZ, posX, posY, posZ).sort();
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
    }
}
