package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Objects;

final class BedrockPredVector extends PredVector {
    private final BedrockMovementInputFactory.Input input;
    private final BedrockMovementResult movementResult;
    private final BedrockSimulation.Input simulationInput;
    private final BedrockMobJumpComponentState mobJumpComponent;

    BedrockPredVector(
            BedrockMovementInputFactory.Input input,
            BedrockMovementResult movementResult,
            BedrockMobJumpComponentState mobJumpComponent,
            PredVector source,
            Vec3 candidateDelta) {
        this(input, null, movementResult, mobJumpComponent, source, candidateDelta);
    }

    BedrockPredVector(
            BedrockMovementInputFactory.Input input,
            BedrockSimulation.Input simulationInput,
            BedrockMovementResult movementResult,
            BedrockMobJumpComponentState mobJumpComponent,
            PredVector source,
            Vec3 candidateDelta) {
        super(
                Objects.requireNonNull(candidateDelta, "candidateDelta"),
                Objects.requireNonNull(source, "source"),
                "bedrock travel candidate");
        this.input = Objects.requireNonNull(input, "input");
        this.simulationInput = simulationInput;
        this.movementResult = Objects.requireNonNull(movementResult, "movementResult");
        this.mobJumpComponent = mobJumpComponent == null ? BedrockMobJumpComponentState.DEFAULT : mobJumpComponent;
        if (movementResult.groundJumpApplied()) {
            setJump();
        }
    }

    BedrockMovementInputFactory.Input input() {
        return input;
    }

    BedrockSimulation.Input simulationInput() {
        return simulationInput;
    }

    BedrockMovementResult movementResult() {
        return movementResult;
    }

    BedrockMobJumpComponentState mobJumpComponent() {
        return mobJumpComponent;
    }

    @Override
    public SimpleCollisionBox collisionIgnoredExtents(Vec3 target) {
        Vec3d previous = movementResult.previousState().physicalFeetPosition();
        Vec3d rawMove = movementResult.rawPredictedPhysicalFeetPosition().subtract(previous);
        return inputExtents(rawMove, horizontalInputRadius());
    }

    static SimpleCollisionBox inputExtents(Vec3d rawMove, double radius) {
        return new SimpleCollisionBox(
                rawMove.x() - radius,
                rawMove.y(),
                rawMove.z() - radius,
                rawMove.x() + radius,
                rawMove.y(),
                rawMove.z() + radius);
    }

    @Override
    public double horizontalInputRadius() {
        return movementResult.horizontalInputRadius();
    }

    @Override
    public Vec3 collisionStuckSpeedMultiplier(SimulationContext context) {
        return new Vec3(1.0D, 1.0D, 1.0D);
    }
}
