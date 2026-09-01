package ac.grim.grimac.checks.impl.prediction.checks;

import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.player.GrimPlayer;

public interface EngineCheck {
    void handleResult(GrimPlayer player, PredictionResult result, PredictionResult lastResult);
}
