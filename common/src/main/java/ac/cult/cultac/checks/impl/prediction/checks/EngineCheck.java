package ac.cult.cultac.checks.impl.prediction.checks;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.player.CultPlayer;

public interface EngineCheck {
    void handleResult(CultPlayer player, PredictionResult result, PredictionResult lastResult);
}
