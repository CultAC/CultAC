package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.EntityPositionWorlds;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.UncertaintyHelper;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.JavaCollisionState;
import ac.cult.cultac.utils.nmsutil.SneakEdgeBackoff;
import java.util.ArrayList;
import java.util.List;

/**
 * Entity#move run forward on a Java candidate whose travel holds its exact input (1.21.11 Entity.java:705-741): the
 * stuck multiplier scales the delta, Player#maybeBackOffFromEdge shortens it, Entity#collide clips it against the
 * start pose's box in Direction#axisStepOrder and steps up through Entity#collectCandidateStepUpHeights, and the setPos
 * guard drops a move shorter than 1.0E-7. Player, vehicle and block-entity moves share this resolver and use each
 * entity's compensated client-tick bounding box.
 */
public final class JavaForwardMove {
    private static final Vec3 NO_STUCK_SPEED = new Vec3(1.0D, 1.0D, 1.0D);

    private JavaForwardMove() {}

    /** A forward move: the trace at its end and the collision that got it there. */
    public record Result(MovementTrace trace, JavaMoveCollision collision) {}

    /** Every Java movement, including vehicles and withheld-position ticks, uses the forward resolver. */
    public static boolean appliesTo(PredictionResult result) {
        return result.getInitialStartingVel() != null
                && result.getPlayer() != null
                && !result.getPlayer().isBedrockMovement();
    }

    /**
     * Entity#move for {@code trace}, the candidate's movement with every handler applied and collisions ignored, toward
     * {@code target}. A late block-entity pass replays travel and its subsequent moves through this same resolver.
     */
    public static Result move(CultPlayer player, PredictionResult result, MovementTrace trace, Vec3 target) {
        SimulationContext context = result.getSimulationContext();
        if (!result.getInitialStartingVel().hasTravelInput() && context.getVehicle() == null) {
            // Select the existing input allowance before collision, so it cannot move through a clipped face later.
            trace = trace.withVelocityShift(
                    UncertaintyHelper.handleCircular(trace.position(), target, result.getHorizontalInputRadius()));
        }
        if (context.isNoPhysics()) {
            // Entity#move sets the position directly, before stuck speed, backoff, collision or its setPos guard.
            Vec3 delta = trace.position().subtract(trace.collisionBaseOffset());
            return new Result(
                    trace.withPreCollisionMovement(delta), JavaMoveCollision.of(delta, delta, NO_STUCK_SPEED, List.of()));
        }
        Vec3 movementScale = trace.position().collisionStuckSpeedMultiplier(context);
        if (movementScale == null) movementScale = NO_STUCK_SPEED;
        MovementTrace ranged = trace;
        trace = withRangeEnd(ranged, target, movementScale);
        if (context.getPassAfterTravel() != null) {
            JavaPassAfterTravel pass =
                    JavaPassAfterTravel.select(player, result, trace, context.getPassAfterTravel(), target);
            if (pass != null && JavaVelocityRange.fitsReport(ranged.position())) {
                // The report holds the pass's shove after the travel: fit the travel to the rest of it.
                Vec3 shove = JavaMoveCollision.unscale(pass.movement(), context.getLastStuckSpeed());
                MovementTrace refit = withRangeEnd(ranged, target.subtract(shove), movementScale);
                JavaPassAfterTravel refitPass =
                        JavaPassAfterTravel.select(player, result, refit, context.getPassAfterTravel(), target);
                if (refitPass != null) {
                    trace = refit;
                    pass = refitPass;
                }
            }
            if (pass != null) {
                Vec3 stuck = context.getLastStuckSpeed();
                Vec3 movement = JavaMoveCollision.unscale(
                        pass.result().state().position().subtract(context.getStart()), stuck);
                Vec3 shove = pass.movement();
                MovementTrace moved = new MovementTrace(
                        trace.position().with(movement, "travel then block entities"),
                        trace.positionOnlyDelta().add(shove),
                        JavaMoveCollision.unscale(shove, stuck),
                        trace.preCollisionVelocity(),
                        pass.collision().attempted(),
                        pass);
                return new Result(shoveBox(player, result, ranged, moved, pass, target, movementScale), pass.collision());
            }
        }
        PredVector position = trace.position();
        Vec3 base = trace.collisionBaseOffset();
        Vec3 stuck = position.collisionStuckSpeedMultiplier(context);
        if (stuck == null) stuck = NO_STUCK_SPEED;
        // Entity#move scales the delta while stuckSpeedMultiplier.lengthSqr() > 1.0E-7; an unset multiplier is all
        // ones.
        Vec3 delta = position.subtract(base).multiply(stuck);
        delta = SneakEdgeBackoff.travelMovement(player, result, delta);

        SimpleCollisionBox box = context.getFromActualPose().offset(base.multiply(stuck));
        JavaCollisionState actor = JavaFallDistance.beforeMove(player, context, result.getInitialStartingVel());
        double stepHeight = result.getInitialStartingVel().maxUpStep(player);
        Vec3 movementTarget = target.subtract(base);
        JavaMoveCollision best = null;
        double distance = Double.POSITIVE_INFINITY;
        for (boolean onGround : context.getLastOnGround().getStates()) {
            for (JavaMoveCollision candidate : collisions(player, context, box, delta, onGround, stepHeight, actor)) {
                double offset = JavaMoveCollision.unscale(shown(delta, candidate.collided()), stuck)
                        .distanceToSqr(movementTarget);
                if (best == null || offset < distance) {
                    best = candidate;
                    distance = offset;
                }
            }
        }
        Vec3 bestCollided = best.collided();
        JavaMoveCollision collision = JavaMoveCollision.of(delta, bestCollided, stuck, best.entities());
        Vec3 unscaled = JavaMoveCollision.unscale(bestCollided, stuck);
        PredVector moved = position.withXYZ(unscaled.x, unscaled.y, unscaled.z, "Entity#collide");
        if (shown(delta, bestCollided) == Vec3.ZERO) moved = moved.with(Vec3.ZERO, "Entity#move setPos guard");
        if (base.lengthSqr() > 1.0E-14) moved = moved.add(base, "external movement collision base");
        // Entity#move records the attempted delta after the backoff for checkInsideBlocks' axis order.
        return new Result(trace.withPosition(moved).withPreCollisionMovement(delta), collision);
    }

    /**
     * A ranged travel that a block-entity pass then moved: the shove hides which velocity the client travelled with,
     * and need not grow with it. The range's own-velocity candidate accepts the report anywhere between where its low,
     * own and high travels end, as position only; the shove is position, so velocity and checks keep its own move.
     */
    private static MovementTrace shoveBox(
            CultPlayer player,
            PredictionResult result,
            MovementTrace ranged,
            MovementTrace moved,
            JavaPassAfterTravel pass,
            Vec3 target,
            Vec3 movementScale) {
        PredVector candidate = ranged.position();
        if (candidate.getVelocityRange() == null || candidate.getRangeEnd(0) != JavaVelocityRange.POINT) return moved;
        SimulationContext context = result.getSimulationContext();
        Vec3 end = pass.result().state().position();
        SimpleCollisionBox ends = new SimpleCollisionBox(end.x, end.y, end.z, end.x, end.y, end.z);
        for (int sign : new int[] {-1, 1}) {
            MovementTrace corner = withRangeEnd(
                    ranged.withPosition(candidate.withRangeEnds(sign, sign, sign)), target, movementScale);
            JavaPassAfterTravel other =
                    JavaPassAfterTravel.select(player, result, corner, context.getPassAfterTravel(), target);
            if (other == null) return moved;
            Vec3 at = other.result().state().position();
            ends = ends.union(new SimpleCollisionBox(at.x, at.y, at.z, at.x, at.y, at.z));
        }
        Vec3 stuck = context.getLastStuckSpeed();
        Vec3 report = context.getStart().add(target.multiply(stuck));
        if (report.x < ends.minX - 1.0E-9 || report.x > ends.maxX + 1.0E-9
                || report.y < ends.minY - 1.0E-9 || report.y > ends.maxY + 1.0E-9
                || report.z < ends.minZ - 1.0E-9 || report.z > ends.maxZ + 1.0E-9) return moved;
        Vec3 gap = report.subtract(end);
        if (gap.lengthSqr() <= 1.0E-14) return moved;
        Vec3 unscaled = JavaMoveCollision.unscale(gap, stuck);
        return new MovementTrace(
                moved.position().add(unscaled, SHOVE_BOX),
                moved.positionOnlyDelta().add(gap),
                moved.collisionBaseOffset().add(unscaled),
                moved.preCollisionVelocity(),
                moved.preCollisionMovement(),
                moved.passAfterTravel());
    }

    /** The trace reason of a report a block-entity pass's shove box accepted; its carry keeps the velocity range. */
    public static final String SHOVE_BOX = "block entity shove box";

    /**
     * The candidate's velocity from its range end (JavaVelocityRange) toward {@code target}, as deltaMovement. Entity#move
     * receives the fully transformed travel (including glide/boat/fluid changes), then scales it; the late-pass replay
     * consumes that same delta rather than the tick's starting velocity.
     */
    private static MovementTrace withRangeEnd(MovementTrace trace, Vec3 target, Vec3 movementScale) {
        trace = trace.withVelocityShift(JavaVelocityRange.select(trace.position(), target));
        return trace.withPreCollisionMovement(
                trace.position().subtract(trace.collisionBaseOffset()).multiply(movementScale));
    }

    /** Entity#collide for one concrete move; stepping is part of that collision, never a second prediction path. */
    public static Vec3 collide(
            CultPlayer player,
            SimpleCollisionBox box,
            Vec3 delta,
            boolean onGround,
            double stepHeight,
            JavaCollisionState actor,
            List<SimpleCollisionBox> entities) {
        return Collisions.collideEntity(player, box, delta, onGround, stepHeight, actor, entities);
    }

    /** Resolves each valid collision world independently at this client-tick boundary. */
    static List<JavaMoveCollision> collisions(
            CultPlayer player,
            SimulationContext context,
            SimpleCollisionBox box,
            Vec3 delta,
            boolean onGround,
            double stepHeight,
            JavaCollisionState actor) {
        SimpleCollisionBox region =
                EntityPositionWorlds.withStepEntities(player, box.copy().expandToCoordinate(delta.x, delta.y, delta.z));
        var mover = context.getVehicle() == null ? player.compensatedEntities.getSelf() : context.getVehicle();
        var results = new ArrayList<JavaMoveCollision>();
        for (List<SimpleCollisionBox> entities : EntityPositionWorlds.forActor(player, mover, Collisions.feet(box), region)) {
            Vec3 collided = collide(player, box, delta, onGround, stepHeight, actor, entities);
            results.add(JavaMoveCollision.of(delta, collided, NO_STUCK_SPEED, entities));
        }
        return results;
    }

    /** Entity#move's setPos guard: the collided move, or zero where it skips setPos for a move under 1.0E-7. */
    private static Vec3 shown(Vec3 attempted, Vec3 collided) {
        double length = collided.lengthSqr();
        boolean moves = length > 1.0E-7D || attempted.lengthSqr() - length < 1.0E-7D;
        return moves ? collided : Vec3.ZERO;
    }
}
