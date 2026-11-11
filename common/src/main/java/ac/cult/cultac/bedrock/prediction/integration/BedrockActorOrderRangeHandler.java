package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.api.BedrockActorOrderRange;
import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.UncertaintyHandler;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;

/**
 * A candidate whose end the order of its solid actors gives a range ends anywhere in it: the claimed end is taken at
 * the point of the range closest to it. Only the position moves; the candidate's velocity and collision are those of
 * every order's end along a collided axis.
 */
final class BedrockActorOrderRangeHandler implements UncertaintyHandler {
    static final BedrockActorOrderRangeHandler INSTANCE = new BedrockActorOrderRangeHandler();

    private BedrockActorOrderRangeHandler() {}

    @Override
    public MovementTrace handleMovementTrace(
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastResult,
            MovementTrace trace,
            Vec3 end) {
        return trace.withPosition(handleUncertainty(player, valid, result, context, lastResult, trace.position(), end));
    }

    @Override
    public PredVector handleUncertainty(
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastResult,
            PredVector start,
            Vec3 end) {
        BedrockActorOrderRange range = range(result.getInitialStartingVel());
        if (range == null || end == null) return start;
        var closest = range.closest(BedrockVectorAdapter.toBedrock(start), BedrockVectorAdapter.toBedrock(end));
        return start.withXYZOrSelf(closest.x(), closest.y(), closest.z(), "solid actor order range");
    }

    /**
     * The state {@code movement} carries on a replayed tick that ended at {@code claimed}, displaced by
     * {@code external}: the point of its range closest to the claimed end, placed as a live tick places the end
     * {@link #handleUncertainty} accepts; a move without a range, or claimed at its own end, carries its own end.
     */
    static BedrockMovementState replayedEnd(BedrockMovementResult movement, Vec3d external, Vec3d claimed) {
        BedrockActorOrderRange range = movement.solidActorOrderRange();
        if (range == null) return movement.predictedState().withExternalDisplacement(external);
        Vec3d previous = movement.previousState().physicalFeetPosition();
        Vec3d own = movement.predictedPosition().subtract(previous);
        Vec3d closest = range.closest(own, claimed.subtract(external).subtract(previous));
        return closest.equals(own)
                ? movement.predictedState().withExternalDisplacement(external)
                : BedrockValidationSelectedState.relocated(movement, external, previous.add(closest.add(external)));
    }

    /** The range {@code vector}'s candidate carries, or null. */
    static BedrockActorOrderRange range(PredVector vector) {
        PredVector candidate = vector == null ? null : vector.firstInLineage(BedrockPredVector.class);
        return candidate instanceof BedrockPredVector bedrock
                ? bedrock.movementResult().solidActorOrderRange()
                : null;
    }
}
