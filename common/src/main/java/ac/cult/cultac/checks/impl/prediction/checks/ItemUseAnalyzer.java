package ac.cult.cultac.checks.impl.prediction.checks;

import ac.cult.cultac.checks.impl.prediction.ItemUseBurstResult;
import ac.cult.cultac.checks.impl.prediction.ItemUseTickResult;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.player.CultPlayer;

/** Evaluates the existing collision/velocity candidate; it does not run another simulation. */
public final class ItemUseAnalyzer implements EngineCheck {
    @Override
    public void handleResult(CultPlayer player, PredictionResult result, PredictionResult lastResult) {
        var context = result.getSimulationContext();
        var state = context.getItemUse();
        if (!player.usesModernJavaItemUse() || context.getVehicle() != null || context.usesFallFlyingMovement()) return;
        ItemUseBurstResult residual = null;
        if (state.required() || state.legalMultiplier() < 1) {
            residual = ItemUseInput.residual(result);
            double legal = ItemUseInput.offset(result, residual, state.legalMultiplier());
            double slowed = state.legalMultiplier() == state.requiredMultiplier()
                    ? legal
                    : ItemUseInput.offset(result, residual, state.requiredMultiplier());
            result.setItemUseResult(new ItemUseTickResult(state, legal, slowed));
        }
        // Reuse this collision candidate for the server-view use balance. It is evidence only;
        // it does not change the legal client movement or NoSlow flags.
        if (context.isServerUseTracked()) {
            result.setItemUseBurstResult(residual != null ? residual : ItemUseInput.residual(result));
        }
    }
}
