package ac.grim.grimac.bedrock.prediction.simulation.reconciliation;

import ac.grim.grimac.bedrock.prediction.api.BedrockMovementResult;
import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.simulation.travel.BedrockTravelHorizontalControl;
import ac.grim.grimac.bedrock.prediction.state.BedrockMovementState;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class BedrockAcceptedDiffHorizontalVelocity {
    private static final double VELOCITY_EPSILON = 1.0E-12D;

    private BedrockAcceptedDiffHorizontalVelocity() {
    }

    static List<AxisVelocity> resolve(
        BedrockAcceptedEndpointEvidence evidence,
        boolean selectedXCollision,
        boolean selectedZCollision
    ) {
        BedrockMovementResult movementResult = evidence.movementResult();
        BedrockMovementState state = evidence.state();
        Vec3d acceptedDiff = evidence.acceptedDiff();
        Set<AxisVelocity> candidates = new LinkedHashSet<>();
        for (double horizontalFriction : horizontalFrictionCandidates(movementResult)) {
            AxisVelocity horizontalVelocity = acceptedDiffHorizontalVelocity(
                acceptedDiff,
                horizontalFriction
            );
            candidates.addAll(blockedAxisCandidates(
                movementResult,
                state,
                acceptedDiff,
                horizontalVelocity,
                selectedXCollision,
                selectedZCollision));
        }
        return List.copyOf(candidates);
    }

    private static AxisVelocity acceptedDiffHorizontalVelocity(
        Vec3d acceptedDiff,
        double horizontalFriction
    ) {
        return new AxisVelocity(
            acceptedDiff.x() * horizontalFriction,
            acceptedDiff.z() * horizontalFriction);
    }

    private static double[] horizontalFrictionCandidates(BedrockMovementResult movementResult) {
        if (!movementResult.selectedWaterTravel()) {
            return new double[] {movementResult.horizontalFriction()};
        }
        return new double[] {
            BedrockTravelHorizontalControl.waterHorizontalDrag(
                movementResult.movementContext(), false),
            BedrockTravelHorizontalControl.waterHorizontalDrag(
                movementResult.movementContext(), true)
        };
    }

    private static List<AxisVelocity> blockedAxisCandidates(
        BedrockMovementResult movementResult,
        BedrockMovementState state,
        Vec3d acceptedDiff,
        AxisVelocity velocity,
        boolean selectedXCollision,
        boolean selectedZCollision
    ) {
        double[] xCandidates = axisVelocityCandidates(
            state,
            acceptedDiff.x(),
            state.collisionFlags().xCollision()
                || movementResult.predictedState().collisionFlags().xCollision()
                || selectedXCollision,
            velocity.x());
        double[] zCandidates = axisVelocityCandidates(
            state,
            acceptedDiff.z(),
            state.collisionFlags().zCollision()
                || movementResult.predictedState().collisionFlags().zCollision()
                || selectedZCollision,
            velocity.z());
        Set<AxisVelocity> candidates = new LinkedHashSet<>();
        for (double velocityX : xCandidates) {
            for (double velocityZ : zCandidates) {
                candidates.add(new AxisVelocity(velocityX, velocityZ));
            }
        }
        return List.copyOf(candidates);
    }

    private static double[] axisVelocityCandidates(
        BedrockMovementState state,
        double acceptedAxisDiff,
        boolean axisCollision,
        double velocity
    ) {

        if (Math.abs(acceptedAxisDiff) > VELOCITY_EPSILON) {
            return axisCollision
                ? new double[] {velocity, 0.0D}
                : new double[] {velocity};
        }
        return axisCollision || state.collisionFlags().horizontalBlockContact()
            ? new double[] {0.0D}
            : new double[] {velocity};
    }

    record AxisVelocity(double x, double z) {
        Vec3d withY(double y) {
            return new Vec3d(x, y, z);
        }
    }
}
