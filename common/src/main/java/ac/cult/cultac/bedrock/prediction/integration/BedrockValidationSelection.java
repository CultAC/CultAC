package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import net.minecraft.world.phys.Vec3;

record BedrockValidationSelection(
        Vec3d previousPosition,
        BedrockAuthInputFrame authFrame,
        Vec3 validationSelectedDelta,
        Vec3d validationSelectedPosition,
        Vec3 packetPosition
) {
    static BedrockValidationSelection from(PredictionResult result, BedrockMovementResult movementResult) {
        if (result == null || movementResult == null) {
            return null;
        }
        Vec3 validationSelectedDelta = result.getAcceptedClosestToTarget();
        Vec3d previousPosition = movementResult.previousState().physicalFeetPosition();
        Vec3d validationSelectedPosition = new Vec3d(
                previousPosition.x() + validationSelectedDelta.x,
                previousPosition.y() + validationSelectedDelta.y,
                previousPosition.z() + validationSelectedDelta.z);
        BedrockAuthInputFrame authFrame = bedrockInput(result);
        Vec3 packetPosition = authFrame == null ? null : authFrame.getPosition();
        return new BedrockValidationSelection(
                previousPosition,
                authFrame,
                validationSelectedDelta,
                validationSelectedPosition,
                packetPosition);
    }

    private static BedrockAuthInputFrame bedrockInput(PredictionResult result) {
        return result.getSimulationContext() == null
                ? null
                : result.getSimulationContext().getBedrockInput();
    }
}
