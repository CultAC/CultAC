package ac.grim.grimac.utils.anticheat.update;

import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.PredictionCommit;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PredictionComplete {
    private PredictionResult predictionResult;
    private PositionUpdate positionUpdate;
    private PredictionCommit preparedCommit;

    public PredictionComplete(PositionUpdate update) {
        this.positionUpdate = update;
    }

    public PredictionComplete(PredictionResult result) {
        this.predictionResult = result;
    }

    public PredictionComplete(PredictionResult result, PredictionCommit preparedCommit) {
        this.predictionResult = result;
        this.preparedCommit = preparedCommit;
    }

    public boolean isTeleport() {
        return predictionResult == null || predictionResult.isTeleport();
    }

    public boolean isExempt() {
        return predictionResult != null && predictionResult.isExempt();
    }
}
