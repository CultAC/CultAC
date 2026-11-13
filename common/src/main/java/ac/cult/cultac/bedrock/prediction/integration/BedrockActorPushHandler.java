package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.UncertaintyHandler;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;

/**
 * A client may show a solid actor beside the player a little off Cult's box for it (a lerp step for 1.26.60 and later,
 * anywhere its history allows before), and pushes the player out of it from there, away from the actor, by no more
 * than the actor's buffer and its depenetration limit: such a tick ends within that push of the candidate's end,
 * sideways away from the actor or down, never up. Where the actor blocks less, or the player steps onto it, the
 * candidates simulate the move with the actor where the client may show it, or without it.
 */
final class BedrockActorPushHandler implements UncertaintyHandler {
    static final BedrockActorPushHandler INSTANCE = new BedrockActorPushHandler();

    private BedrockActorPushHandler() {}

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
        if (end == null || !touched(player, context)) return start;
        long tick = context.getBedrockInput().getClientTick();
        var buffer = player.bedrockState.solidActorBuffer;
        return start.withXYZOrSelf(
                within(start.x, end.x, buffer.actorPush(tick, 0, false), buffer.actorPush(tick, 0, true)),
                within(start.y, end.y, buffer.actorPush(tick, 1, false), 0.0D),
                within(start.z, end.z, buffer.actorPush(tick, 2, false), buffer.actorPush(tick, 2, true)),
                "solid actor push");
    }

    static boolean touched(CultPlayer player, SimulationContext context) {
        return player != null
                && player.bedrockState != null
                && context != null
                && context.getBedrockInput() != null
                && player.bedrockState.solidActorBuffer.actorTouched(context.getBedrockInput().getClientTick());
    }

    /** The claimed {@code end} on one axis, held within {@code below} under and {@code above} over {@code own}. */
    private static double within(double own, double end, double below, double above) {
        return Math.max(own - below, Math.min(own + above, end));
    }
}
