package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.Ladder;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldStageBuilder;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.latency.ClientBlockEntityPass;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BlockProperties;
import ac.cult.cultac.utils.nmsutil.NextTickVelocityDeriver;
import ac.cult.cultac.utils.nmsutil.SneakEdgeBackoff;
import java.util.ArrayList;
import java.util.List;

/**
 * From 26.3, the client tick's Level#tickBlockEntities after a candidate travel and before LocalPlayer#sendChanges:
 * the state the travel left at {@code travel}'s position, the pose Player#updatePlayerPose left, and what the pass
 * did from there. Selection and carry both use the travel's callbacks, stuck speed and fall distance.
 */
public record JavaPassAfterTravel(
        JavaPassState travel,
        Pose pose,
        JavaBlockEntityPass.Result result,
        boolean travelHorizontalCollision,
        Vec3 input,
        boolean travelBackedOffX,
        boolean travelBackedOffZ,
        JavaMoveCollision collision) {
    public JavaPassAfterTravel(
            JavaPassState travel,
            Pose pose,
            JavaBlockEntityPass.Result result,
            boolean travelHorizontalCollision,
            Vec3 input,
            boolean travelBackedOffX,
            boolean travelBackedOffZ) {
        this(travel, pose, result, travelHorizontalCollision, input, travelBackedOffX, travelBackedOffZ, null);
    }
    /** The pass's displacement from the travel's end. */
    public Vec3 movement() {
        return result.movement(travel.position());
    }

    /**
     * LivingEntity#moveRelative adds input before Entity#move, and Minecraft#tick runs the block entities after
     * that travel. A shove cannot be computed from the input-free velocity: input can enter or leave its swept
     * shape, or change a full shove into a partial one. Replay the possible KeyboardInput directions first.
     * When the pass hides their difference, the reported keys distinguish the otherwise identical positions;
     * they never admit a movement outside these independently generated candidates.
     */
    public static JavaPassAfterTravel select(
            CultPlayer player, PredictionResult prediction, MovementTrace trace, ClientBlockEntityPass pass, Vec3 end) {
        SimulationContext context = prediction.getSimulationContext();
        // VelocityTransformer/BoatTransform already added a controlled root's exact ridden input, and
        // JavaTravelInputs a player candidate's exact input.
        List<Vec3> inputs = context.getVehicle() == null
                ? JavaTravelInputs.replayInputs(player, context, prediction.getInitialStartingVel())
                : List.of(Vec3.ZERO);
        List<Vec3> reported = context.getVehicle() == null
                ? JavaTravelInputs.reportedReplayInputs(player, context, prediction.getInitialStartingVel())
                : List.of(Vec3.ZERO);
        Vec3 target = context.getStart().add(end.multiply(context.getLastStuckSpeed()));
        JavaPassAfterTravel best = null;
        double distance = Double.POSITIVE_INFINITY;
        double keyDistance = Double.POSITIVE_INFINITY;
        for (Vec3 input : inputs) {
            for (JavaPassAfterTravel candidate : replay(player, prediction, trace, pass, input)) {
                Vec3 report = JavaVehicleReport.followsTarget(player, context)
                        ? JavaVehicleReport.targetPosition(context, candidate)
                        : candidate.result().state().position();
                double offset = report.distanceToSqr(target);
                double keys = reported.isEmpty() ? input.lengthSqr() : distanceToInput(input, reported);
                if (best == null
                        || offset < distance - 1.0E-14D
                        || Math.abs(offset - distance) <= 1.0E-14D && keys < keyDistance) {
                    best = candidate;
                    distance = offset;
                    keyDistance = keys;
                }
            }
        }
        return best;
    }

    private static double distanceToInput(Vec3 input, List<Vec3> reported) {
        double distance = Double.POSITIVE_INFINITY;
        for (Vec3 key : reported) distance = Math.min(distance, input.distanceToSqr(key));
        return distance;
    }

    static List<JavaPassAfterTravel> replay(
            CultPlayer player,
            PredictionResult prediction,
            MovementTrace trace,
            ClientBlockEntityPass pass,
            Vec3 input) {
        SimulationContext context = prediction.getSimulationContext();
        var results = new ArrayList<JavaPassAfterTravel>();
        for (boolean climbing : Ladder.clampsDeltaMovement(context).getStates())
            results.addAll(replayMove(player, prediction, trace, pass, input, climbing));
        return results;
    }

    /** The input-inclusive handleOnClimbable runs before Entity#move, then stuck speed scales the move. */
    static Vec3 travelDelta(
            CultPlayer player, PredictionResult prediction, MovementTrace trace, Vec3 input, boolean climbing) {
        var context = prediction.getSimulationContext();
        Vec3 scale = context.getLastStuckSpeed();
        if (!climbing) return trace.preCollisionMovement().add(input.multiply(scale));
        Vec3 delta = trace.preCollisionMovement()
                .multiply(1 / scale.x, 1 / scale.y, 1 / scale.z)
                .add(input);
        return Ladder.handleOnClimbable(player, context, delta).multiply(scale);
    }

    private static List<JavaPassAfterTravel> replayMove(
            CultPlayer player,
            PredictionResult prediction,
            MovementTrace trace,
            ClientBlockEntityPass pass,
            Vec3 input,
            boolean climbing) {
        SimulationContext context = prediction.getSimulationContext();
        Vec3 originalDelta = travelDelta(player, prediction, trace, input, climbing);
        Vec3 base = trace.collisionBaseOffset().multiply(context.getLastStuckSpeed());
        var results = new ArrayList<JavaPassAfterTravel>();
        for (TravelMove movedTravel : collideTravel(player, prediction, originalDelta, base)) {
            results.addAll(replayMove(player, prediction, trace, pass, input, climbing, originalDelta, base, movedTravel));
        }
        return results;
    }

    private static List<JavaPassAfterTravel> replayMove(
            CultPlayer player,
            PredictionResult prediction,
            MovementTrace trace,
            ClientBlockEntityPass pass,
            Vec3 input,
            boolean climbing,
            Vec3 originalDelta,
            Vec3 base,
            TravelMove movedTravel) {
        SimulationContext context = prediction.getSimulationContext();
        Vec3 delta = movedTravel.attempted();
        Vec3 travel = movedTravel.movement();
        double travelLength = travel.lengthSqr();
        boolean moved = travelLength > 1.0E-7D || delta.lengthSqr() - travelLength < 1.0E-7D;
        Vec3 moveStart = context.getStart().add(base);
        Vec3 end = moved ? moveStart.add(travel) : moveStart;
        boolean onGround = delta.y < 0.0D && delta.y != travel.y;
        Pose pose = context.getVehicle() != null
                ? player.pose
                : player.updatedPose(
                        context.getWorldData().isSwimming(),
                        context.isGliding() && !context.stopsFallFlyingAfterTravel(),
                        end.x,
                        end.y,
                        end.z);
        var support = player.checkManager
                .getSimulationProcessor()
                .mainSupportingBlockAfter(
                        context.getLastTickMainSupportingBlockData(),
                        context.getActualPoseAt(end),
                        // Entity#setOnGroundWithMovement receives the collided move even when setPos was skipped.
                        travel,
                        onGround,
                        end);
        SimulationContext travelContext = context.forTravelReplay(end);
        travelContext.getWorldData().setMainSupportingBlockPos(support);
        travelContext.getWorldData().setOnBlock(BlockProperties.getOnPos(player, support, end));
        travelContext.getWorldData().setClimbing(WorldStageBuilder.climbingAtPosition(player, travelContext, end));
        Vec3 scale = context.getLastStuckSpeed();
        Vec3 movement = end.subtract(context.getStart());
        Vec3 unscaled = movement.multiply(1 / scale.x, 1 / scale.y, 1 / scale.z);
        var replay = new PredictionResult(
                player,
                prediction.getInitialStartingVel(),
                travelContext,
                unscaled,
                prediction.getSetBackData(),
                List.of(),
                List.of());
        replay.setCollideAxisData(JavaMoveCollision.of(
                delta, travel, scale, movedTravel.collision().entities()));
        ValidMovements.useTravelReplay(
                replay,
                new MovementTrace(
                        new PredVector(unscaled, trace.position(), "travel before block entities"),
                        Vec3.ZERO,
                        Vec3.ZERO,
                        trace.preCollisionVelocity(),
                        trace.preCollisionMovement()),
                input,
                climbing,
                delta);
        var fall = JavaFallDistance.afterCollision(player, replay, movement);
        replay.setJavaCommittedMove(fall);
        var scan = JavaInsideBlockEffects.travelScan(player, travelContext, context.getStart(), end, delta);
        var velocities = NextTickVelocityDeriver.beforeBlockEffects(
                player, replay, player.checkManager.getSimulationProcessor().getLastPrediction(), null);
        var results = new ArrayList<JavaPassAfterTravel>(velocities.size());
        for (Vec3 velocity : velocities) {
            for (var outcome : JavaMovementEngine.beforeBlockEntityPass(player, replay, fall, scan, end, velocity)) {
                var effects = outcome.effects();
                var state = new JavaPassState(
                        end,
                        effects.velocity(),
                        onGround,
                        support,
                        effects.stuckSpeed(),
                        effects.fallDistance(),
                        player.checkManager
                                .getSimulationProcessor()
                                .getPistonDeltas(
                                        context.getVehicle() == null
                                                ? player.compensatedEntities.getSelf()
                                                : context.getVehicle()),
                        effects.powderContact(),
                        fall.touchingWater(),
                        fall.touchingLava(),
                        fall.playerEyeFluidContact());
                results.add(new JavaPassAfterTravel(
                        state,
                        pose,
                        JavaBlockEntityPass.run(
                                player, context, pass, state, pose, context.isSneaking(), context.getStart()),
                        !CultMath.equal(delta.x, travel.x) || !CultMath.equal(delta.z, travel.z),
                        input,
                        originalDelta.x != delta.x,
                        originalDelta.z != delta.z,
                        JavaMoveCollision.of(
                                delta, travel, scale, movedTravel.collision().entities())));
            }
        }
        return results;
    }

    /** The backoff changes the attempted move while leaving deltaMovement intact. */
    record TravelMove(Vec3 attempted, Vec3 movement, JavaMoveCollision collision) {}

    /** The replayed travel uses Player#maybeBackOffFromEdge, then Entity#collide's actor and entity shapes. */
    static List<TravelMove> collideTravel(CultPlayer player, PredictionResult prediction, Vec3 delta) {
        return collideTravel(player, prediction, delta, Vec3.ZERO);
    }

    private static List<TravelMove> collideTravel(CultPlayer player, PredictionResult prediction, Vec3 delta, Vec3 base) {
        var context = prediction.getSimulationContext();
        delta = SneakEdgeBackoff.travelMovement(player, prediction, delta);
        var box = context.getFromActualPose().offset(base);
        var results = new ArrayList<TravelMove>();
        for (JavaMoveCollision collision : JavaForwardMove.collisions(
                player,
                context,
                box,
                delta,
                context.getLastOnGround().determineOptimistically(),
                player.getMaxUpStep(),
                JavaFallDistance.beforeMove(player, context, prediction.getInitialStartingVel()))) {
            results.add(new TravelMove(delta, collision.collided(), collision));
        }
        return results;
    }
}
