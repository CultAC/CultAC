package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import static ac.cult.cultac.utils.math.VectorUtils.LARGE_MOVEMENT;

import ac.cult.cultac.bedrock.prediction.integration.BedrockMovementEngine;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaForwardMove;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementMath;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaVelocityRange;
import ac.cult.cultac.checks.impl.prediction.stage.UncertaintyPipeline;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.math.CultMath;
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

    /** The collision-ignored trace this candidate's forward Entity#move ran on. */
    private MovementTrace beforeForwardMove;

    /** The selected trace before the debug allowance moved it, or null when the allowance did not run. */
    private MovementTrace beforeDebugAllowance;

    /** This candidate and its debug-allowance velocity readings, once read; see {@link #debugVelocityReadings}. */
    private List<PredictionResult> debugFamily;

    /** On the selected candidate, every tied reading the commit carries; see {@link #debugCarried}. */
    private List<PredictionResult> debugCarried;

    /** How far, in blocks, the debug allowance moved this candidate's position; zero when it fit without it. */
    @Getter
    private Vec3 debugAllowance = Vec3.ZERO;

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
        boolean previous = JavaMovementMath.select(player, initialStartingVel);
        try {
            return transformWithMath(handlers, count, end, lastResult);
        } finally {
            JavaMovementMath.restore(player, previous);
        }
    }

    private MovementTrace transformWithMath(
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
        beforeForwardMove = closestTrace;
        JavaForwardMove.Result moved = JavaForwardMove.move(player, result, closestTrace, result.getTarget());
        result.setCollideAxisData(moved.collision());
        setClosestTrace(withheldReport(moved.trace(), result.getTarget()));
    }

    /** Explicit debug-only position allowance, applied once after the candidate's normal collision resolution. */
    public void applyDebugUncertainty() {
        Vec3 radius = player.debugUncertainty;
        if (radius.x == 0 && radius.y == 0 && radius.z == 0) return;
        // A vehicle following its interpolation target reports that target, not this travel: no gap to allow.
        if (result.getSimulationContext().getVehicle() != null
                && ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaVehicleReport.followsTarget(
                        player, result.getSimulationContext())) return;
        beforeDebugAllowance = closestTrace;
        MovementTrace adjusted = withDebugAllowance(closestTrace, radius);
        debugAllowance = adjusted.positionOnlyDelta().subtract(closestTrace.positionOnlyDelta());
        setClosestTrace(adjusted);
    }

    /**
     * {@code trace} moved toward the report by at most the debug allowance per axis. This is a position tolerance:
     * its gap is position-only, never Entity#deltaMovement.
     */
    private MovementTrace withDebugAllowance(MovementTrace trace, Vec3 radius) {
        Vec3 stuck = result.getSimulationContext().getLastStuckSpeed();
        Vec3 target = result.getTarget();
        PredVector start = trace.position();
        // Prediction coordinates divide out stuck speed; command values remain physical blocks.
        PredVector adjusted = start.withXYZOrSelf(
                CultMath.clamp(target.x, start.x - radius.x / stuck.x, start.x + radius.x / stuck.x),
                CultMath.clamp(target.y, start.y - radius.y / stuck.y, start.y + radius.y / stuck.y),
                CultMath.clamp(target.z, start.z - radius.z / stuck.z, start.z + radius.z / stuck.z),
                "debug uncertainty");
        return trace.withPosition(adjusted)
                .addPositionOnlyDelta(adjusted.subtract(start).multiply(stuck));
    }

    /**
     * The debug allowance cannot tell whether its gap was position the engine missed (a shove) or deltaMovement the
     * engine got wrong, and on an axis a collision or a block-entity pass hides, the report shows no deltaMovement at
     * all. This candidate reads every gap as position. Its velocity readings re-run the same travel through Entity#move
     * with a velocity range of the allowance's reach around this candidate's velocity: the value nearest the report,
     * and the range ends where a collision or a later pass could hide the axis (JavaVelocityRange#ends). Each that ends
     * within the allowance of the report is a complete candidate of its own; the carry keeps every reading, whichever
     * wins, so a slime bounce or a piston-pinned axis keeps the velocities the allowance leaves possible.
     */
    public List<PredictionResult> debugVelocityReadings() {
        if (debugFamily == null) debugFamily = readDebugFamily();
        return debugFamily.isEmpty() ? List.of() : debugFamily.subList(1, debugFamily.size());
    }

    /**
     * Whether this candidate's velocity readings are worth reading: the allowance ran on it and no earlier candidate
     * of {@code candidates} moved the same travel in the same context, whose readings would be the same.
     */
    public boolean readsDebugVelocity(List<PredictionResult> candidates) {
        if (beforeDebugAllowance == null || debugFamily != null) return false;
        for (PredictionResult other : candidates) {
            if (other == result) return true;
            ValidMovements valid = other.getValidMovements();
            if (valid.beforeDebugAllowance != null
                    && other.getSimulationContext() == result.getSimulationContext()
                    && sameCarryTrace(valid.beforeDebugAllowance, beforeDebugAllowance)) return false;
        }
        return true;
    }

    /** Whether {@code other} moved the same travel, before any allowance: its carry would be the same. */
    public boolean sameTravel(ValidMovements other) {
        MovementTrace mine = beforeDebugAllowance != null ? beforeDebugAllowance : closestTrace;
        MovementTrace theirs = other.beforeDebugAllowance != null ? other.beforeDebugAllowance : other.closestTrace;
        return mine != null && theirs != null && sameCarryTrace(mine, theirs);
    }

    /** This candidate and its velocity readings, or empty when the allowance left it none. */
    public List<PredictionResult> debugFamily() {
        return debugFamily == null ? List.of() : debugFamily;
    }

    /** Every candidate the debug allowance leaves as possible as this selected one, itself included. */
    public List<PredictionResult> debugCarried() {
        return debugCarried == null ? debugFamily() : debugCarried;
    }

    /** Sets {@link #debugCarried}: the readings of every travel that explains the report as well as this one. */
    public void carryDebugReadings(List<PredictionResult> carried) {
        debugCarried = carried;
    }

    private List<PredictionResult> readDebugFamily() {
        Vec3 radius = player.debugUncertainty;
        SimulationContext context = result.getSimulationContext();
        if (beforeDebugAllowance == null
                || beforeForwardMove == null
                || player.isBedrockMovement()
                || context.getVehicle() != null
                || context.isNoPhysics()) return List.of();
        Vec3 stuck = context.getLastStuckSpeed();
        Vec3 target = result.getTarget();
        PredVector travel = beforeForwardMove.position();
        // Offsets from the travel's own value to the one this candidate moved with: JavaForwardMove shifts the velocity
        // toward the report, after taking a later block-entity pass's shove out of it.
        Vec3 selected = beforeDebugAllowance.preCollisionVelocity().subtract(beforeForwardMove.preCollisionVelocity());
        double[] reach = {radius.x / stuck.x, radius.y / stuck.y, radius.z / stuck.z};
        double[] low = {selected.x - reach[0], selected.y - reach[1], selected.z - reach[2]};
        double[] high = {selected.x + reach[0], selected.y + reach[1], selected.z + reach[2]};
        PredVector ranged =
                travel.withVelocityRange(new SimpleCollisionBox(low[0], low[1], low[2], high[0], high[1], high[2]));
        // A block-entity pass after the travel can pin any axis for some of the range and not for the rest: a piston
        // pushes a player that travelled less further, to the same place. This travel's own pass does not show which
        // axes, so a pass hides every axis, as JavaVelocityRange#ends reads it. Player#maybeBackOffFromEdge can hide
        // X/Z.
        boolean passHides = context.getPassAfterTravel() != null;
        boolean sneaking = context.isSneaking();
        // JavaVelocityRange#ends also reads the velocities beside a wall or floor inside the range.
        var samples = new ArrayList<>(JavaVelocityRange.ends(
                player, context, ranged, sneaking || passHides, passHides, sneaking || passHides));
        var family = new ArrayList<PredictionResult>();
        family.add(result);
        for (PredVector end : samples) {
            JavaForwardMove.Result moved =
                    JavaForwardMove.move(player, result, beforeForwardMove.withPosition(end), target);
            MovementTrace trace = withheldReport(moved.trace(), target);
            if (!withinReach(trace.position(), target, reach)) continue;
            boolean duplicate = false;
            for (var other : family) duplicate |= sameCarryTrace(trace, other.getValidMovements().beforeDebugAllowance);
            if (duplicate) continue;
            var other = new PredictionResult(
                    player,
                    initialStartingVel,
                    context,
                    target,
                    result.getSetBackData(),
                    result.getRealities(),
                    result.getAnyReality());
            other.setCollideAxisData(moved.collision());
            other.setSneakingInputMultiplier(result.getSneakingInputMultiplier());
            var valid = new ValidMovements(initialStartingVel, player, other, canStep, uncertaintyHandlers);
            valid.collisionIgnoredMaxStartingVelExtents = collisionIgnoredMaxStartingVelExtents;
            valid.reportRadius = reportRadius;
            valid.beforeForwardMove = beforeForwardMove.withPosition(end);
            valid.beforeDebugAllowance = trace;
            // Ranked by how far off the reading was before its own allowance, like every other candidate.
            valid.setClosestTrace(trace);
            other.selectionOffset();
            MovementTrace adjusted = withDebugAllowance(trace, radius);
            valid.debugAllowance = adjusted.positionOnlyDelta().subtract(trace.positionOnlyDelta());
            valid.setClosestTrace(adjusted);
            family.add(other);
        }
        if (family.size() == 1) return List.of();
        List<PredictionResult> shared = List.copyOf(family);
        for (PredictionResult member : shared) member.getValidMovements().debugFamily = shared;
        return shared;
    }

    /** Whether {@code position} lies within {@code reach} of {@code target} on every axis. */
    private static boolean withinReach(Vec3 position, Vec3 target, double[] reach) {
        double epsilon = SimpleCollisionBox.COLLISION_EPSILON;
        return Math.abs(target.x - position.x) <= reach[0] + epsilon
                && Math.abs(target.y - position.y) <= reach[1] + epsilon
                && Math.abs(target.z - position.z) <= reach[2] + epsilon;
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
