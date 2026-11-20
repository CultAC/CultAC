package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;

public interface UncertaintyHandler {
    /**
     * True only if, in this context after this last result, {@link #handleMovementTrace} returns the trace it is given
     * itself, with no side effect, whatever the trace and end. Must not read state that a handler writes.
     */
    default boolean skipsContext(CultPlayer player, SimulationContext context, PredictionResult lastResult) {
        return false;
    }

    default MovementTrace handleMovementTrace(
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastResult,
            MovementTrace trace,
            Vec3 end) {
        return trace.withPosition(handleUncertainty(player, valid, result, context, lastResult, trace.position(), end));
    }

    PredVector handleUncertainty(
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastResult,
            PredVector start,
            Vec3 end);

    /**
     * Replaces the start in {@code xyz} with the position {@link #handleUncertainty} returns for it, with the same
     * side effects, for a start whose flags are {@code carrier}'s and whose lineage since {@code carrier} holds only
     * steps of handlers that implement the form below with the end's coordinates. A handler that does not override that
     * form has no such form.
     */
    default void positionInto(
            double[] xyz,
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastResult,
            PredVector carrier,
            Vec3 end) {
        positionInto(xyz, player, valid, result, context, lastResult, carrier, end.x, end.y, end.z);
    }

    /** The position form above towards an end at {@code endX}, {@code endY}, {@code endZ}. */
    default void positionInto(
            double[] xyz,
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastResult,
            PredVector carrier,
            double endX,
            double endY,
            double endZ) {
        throw new UnsupportedOperationException(getClass().getName());
    }
}
