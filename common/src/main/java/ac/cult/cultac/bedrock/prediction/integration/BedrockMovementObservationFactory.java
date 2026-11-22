package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.BedrockMovementObservation;
import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockForwardTick;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.protocol.BedrockMoveVector;
import ac.cult.cultac.utils.math.Vec3;

final class BedrockMovementObservationFactory {
    private BedrockMovementObservationFactory() {}

    static BedrockMovementObservation fromForwardSelection(
            BedrockMovementResult result,
            Vec3 actual,
            BedrockMovementState selected,
            Vec3 reportedVelocity,
            BedrockMoveVector controls) {
        Vec3d position = selected.physicalFeetPosition();
        Vec3d error = BedrockVectorAdapter.toBedrock(actual).subtract(position);
        double horizontal = Math.hypot(error.x(), error.z());
        double velocity = reportedVelocity == null
                ? 0
                : BedrockForwardTick.finish(result, selected)
                        .velocity()
                        .subtract(BedrockVectorAdapter.toBedrock(reportedVelocity))
                        .length();
        return BedrockMovementObservation.create(
                result,
                actual,
                position,
                new Vec3d(error.x(), 0, error.z()),
                controls == null ? Vec3d.ZERO : new Vec3d(controls.x(), 0, controls.z()),
                0,
                horizontal,
                Math.abs(error.y()),
                error.length(),
                velocity);
    }
}
