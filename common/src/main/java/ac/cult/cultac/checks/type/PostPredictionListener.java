package ac.cult.cultac.checks.type;

import ac.cult.cultac.utils.anticheat.update.PredictionComplete;

public interface PostPredictionListener extends CheckListener {

    default void onPredictionComplete(final PredictionComplete predictionComplete) {}
}
