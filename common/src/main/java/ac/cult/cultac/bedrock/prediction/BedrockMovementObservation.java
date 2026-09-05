package ac.cult.cultac.bedrock.prediction;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import net.minecraft.world.phys.Vec3;

public record BedrockMovementObservation(
        Vec3d actualPosition,
        Vec3d actualDelta,
        Vec3d predictedPosition,
        Vec3d predictedDelta,
        Vec3d rawPredictedDelta,
        Vec3d positionDelta,
        Vec3d rawPositionDelta,
        Vec3d requiredHorizontalInput,
        Vec3d observedHorizontalInput,
        double positionOffset,
        double rawPositionOffset,
        double velocityOffset,
        double requiredHorizontalInputMagnitude,
        double observedHorizontalInputMagnitude,
        double horizontalInputLimit,
        double horizontalInputExcess,
        double verticalOffset,
        double validationOffset
) {
    public static BedrockMovementObservation create(
            BedrockMovementResult result,
            Vec3 actualPhysicalFeet,
            Vec3d predictedPosition,
            Vec3d requiredHorizontalInput,
            Vec3d observedHorizontalInput,
            double horizontalInputLimit,
            double horizontalInputExcess,
            double verticalOffset,
            double validationOffset
    ) {
        if (result == null || actualPhysicalFeet == null || predictedPosition == null
                || requiredHorizontalInput == null || observedHorizontalInput == null) {
            return null;
        }

        BedrockMovementState previous = result.previousState();
        Vec3d actualPosition = new Vec3d(
                actualPhysicalFeet.x,
                actualPhysicalFeet.y,
                actualPhysicalFeet.z
        );
        Vec3d rawPredictedPosition = result.rawPredictedPhysicalFeetPosition();
        long tickDelta = Math.max(1L, result.predictedState().clientTick() - previous.clientTick());
        Vec3d actualDelta = actualPosition.subtract(previous.physicalFeetPosition()).scale(1.0D / tickDelta);
        Vec3d predictedDelta = predictedPosition.subtract(previous.physicalFeetPosition()).scale(1.0D / tickDelta);
        Vec3d rawPredictedDelta = rawPredictedPosition.subtract(previous.physicalFeetPosition()).scale(1.0D / tickDelta);
        Vec3d positionDelta = predictedPosition.subtract(actualPosition);
        Vec3d rawPositionDelta = rawPredictedPosition.subtract(actualPosition);
        double velocityOffset = result.predictedVelocity().subtract(actualDelta).length();
        double requiredHorizontalInputMagnitude = Math.sqrt(requiredHorizontalInput.x() * requiredHorizontalInput.x()
                + requiredHorizontalInput.z() * requiredHorizontalInput.z());
        double observedHorizontalInputMagnitude = Math.sqrt(observedHorizontalInput.x() * observedHorizontalInput.x()
                + observedHorizontalInput.z() * observedHorizontalInput.z());
        return new BedrockMovementObservation(
                actualPosition,
                actualDelta,
                predictedPosition,
                predictedDelta,
                rawPredictedDelta,
                positionDelta,
                rawPositionDelta,
                requiredHorizontalInput,
                observedHorizontalInput,
                positionDelta.length(),
                rawPositionDelta.length(),
                velocityOffset,
                requiredHorizontalInputMagnitude,
                observedHorizontalInputMagnitude,
                horizontalInputLimit,
                horizontalInputExcess,
                verticalOffset,
                validationOffset
        );
    }
}
