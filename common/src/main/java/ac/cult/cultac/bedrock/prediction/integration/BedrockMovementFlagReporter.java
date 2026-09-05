package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.prediction.BedrockMovementObservation;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionResult;
import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.checks.impl.bedrock.BedrockMovement;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.player.CultPlayer;
import java.util.Locale;

final class BedrockMovementFlagReporter {
    private BedrockMovementFlagReporter() {
    }

    public static void addObservationFlag(CultPlayer player, PredictionResult result, BedrockPredictionResult bedrockResult) {
        if (player.bedrockState == null || result == null || result.isExempt() || bedrockResult == null) {
            return;
        }

        BedrockMovementResult movementResult = bedrockResult.movementResult();
        BedrockMovementObservation observation = bedrockResult.observation();
        if (movementResult == null || observation == null) {
            return;
        }

        BedrockMovement check = player.checkManager.getListener(BedrockMovement.class);
        double positionThreshold = CultAPI.INSTANCE.getConfigManager().getBedrockMovementPositionFlagThreshold();
        double offset = observation.validationOffset();
        if (!check.shouldEvaluateOffset(offset, positionThreshold)) {
            return;
        }
        double velocityThreshold = CultAPI.INSTANCE.getConfigManager().getBedrockMovementVelocityFlagThreshold();
        result.addFlag(check, () -> String.format(Locale.ROOT,
                "rawOffset=%.8f positionOffset=%.5f verticalOffset=%.5f threshold=%.5f velocityOffset=%.5f velocityThreshold=%.5f requiredInput=%.5f requiredInputX=%.5f requiredInputZ=%.5f observedInput=%.5f observedInputX=%.5f observedInputZ=%.5f inputLimit=%.5f inputMismatch=%.5f status=%s cause=%s correction=%s",
                observation.rawPositionOffset(),
                observation.positionOffset(),
                observation.verticalOffset(),
                positionThreshold,
                observation.velocityOffset(),
                velocityThreshold,
                observation.requiredHorizontalInputMagnitude(),
                observation.requiredHorizontalInput().x(),
                observation.requiredHorizontalInput().z(),
                observation.observedHorizontalInputMagnitude(),
                observation.observedHorizontalInput().x(),
                observation.observedHorizontalInput().z(),
                observation.horizontalInputLimit(),
                observation.horizontalInputExcess(),
                "REJECTED",
                "ENGINE_PHYSICS",
                "SET_POSITION"), offset);
    }

    static void addEnginePredictionFailureFlag(CultPlayer player, PredictionResult result) {
        if (player.bedrockState == null || result == null || result.isExempt()) {
            return;
        }

        BedrockMovement check = player.checkManager.getListener(BedrockMovement.class);
        double positionThreshold = CultAPI.INSTANCE.getConfigManager().getBedrockMovementPositionFlagThreshold();
        result.addFlag(check, () -> String.format(Locale.ROOT,
                "offset=Infinity rawOffset=Infinity positionOffset=Infinity verticalOffset=Infinity threshold=%.5f velocityOffset=Infinity velocityThreshold=%.5f status=%s cause=%s correction=%s",
                positionThreshold,
                CultAPI.INSTANCE.getConfigManager().getBedrockMovementVelocityFlagThreshold(),
                "REJECTED",
                "ENGINE_PHYSICS",
                "SET_POSITION"), Math.max(1.0D, positionThreshold));
    }
}
