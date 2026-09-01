package ac.grim.grimac.bedrock.prediction.integration;

import ac.grim.grimac.bedrock.prediction.BedrockMovementObservation;
import ac.grim.grimac.bedrock.prediction.BedrockPredictionResult;
import ac.grim.grimac.bedrock.prediction.api.BedrockMovementResult;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.checks.EngineCheck;
import ac.grim.grimac.player.GrimPlayer;

public final class BedrockInputAnalyzer implements EngineCheck {
    @Override
    public void handleResult(GrimPlayer player, PredictionResult result, PredictionResult lastResult) {
        if (player.bedrockState == null || result == null || result.isTeleport()) {
            return;
        }

        BedrockPredictionResult bedrockResult = result.getProfileResult(BedrockPredictionResult.class);
        if (bedrockResult == null) {
            return;
        }

        bedrockResult = withValidationObservation(result, bedrockResult);
        BedrockMovementFlagReporter.addObservationFlag(player, result, bedrockResult);
    }

    private static BedrockPredictionResult withValidationObservation(
            PredictionResult result,
            BedrockPredictionResult bedrockResult
    ) {
        if (bedrockResult.observation() != null || bedrockResult.movementResult() == null) {
            return bedrockResult;
        }
        BedrockMovementResult movementResult = bedrockResult.movementResult();
        BedrockValidationSelection selection = BedrockValidationSelection.from(result, movementResult);
        if (selection == null || selection.packetPosition() == null) {
            return bedrockResult;
        }
        BedrockMovementObservation observation = BedrockMovementObservationFactory.fromValidationSelection(
                movementResult,
                selection.packetPosition(),
                selection.validationSelectedPosition(),
                selection.validationSelectedPosition(),
                selection.authFrame() == null ? null : selection.authFrame().getMoveVector());
        BedrockPredictionResult updated = bedrockResult.withObservation(observation);
        result.setProfileResult(updated);
        return updated;
    }
}
