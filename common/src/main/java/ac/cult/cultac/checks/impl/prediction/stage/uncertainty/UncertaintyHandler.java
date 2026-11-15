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

    /**
     * By default a handler models a change to the client's Entity#deltaMovement, so its shift moves the trace's
     * pre-collision velocity too. Handlers whose movement Entity#move never writes to deltaMovement override this.
     */
    default MovementTrace handleMovementTrace(
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastResult,
            MovementTrace trace,
            Vec3 end) {
        return trace.withVelocityShift(handleUncertainty(player, valid, result, context, lastResult, trace.position(), end),
                context == null ? null : trace.position().collisionStuckSpeedMultiplier(context));
    }

    PredVector handleUncertainty(
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastResult,
            PredVector start,
            Vec3 end);
}
