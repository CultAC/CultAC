package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;

final class BedrockPredVector extends PredVector {
    private static final double INPUT_FRICTION = 0.98F;
    private final BedrockMovementInputFactory.Input input;
    private final BedrockMovementResult movementResult;
    private final BedrockMobJumpComponentState mobJumpComponent;

    BedrockPredVector(
            BedrockMovementInputFactory.Input input,
            BedrockMovementResult movementResult,
            BedrockMobJumpComponentState mobJumpComponent,
            PredVector source,
            Vec3 candidateDelta
    ) {
        super(
                Objects.requireNonNull(candidateDelta, "candidateDelta"),
                Objects.requireNonNull(source, "source"),
                "bedrock travel candidate");
        this.input = Objects.requireNonNull(input, "input");
        this.movementResult = Objects.requireNonNull(movementResult, "movementResult");
        this.mobJumpComponent = mobJumpComponent == null
                ? BedrockMobJumpComponentState.DEFAULT
                : mobJumpComponent;
        if (movementResult.groundJumpApplied()) {
            setJump();
        }
    }

    BedrockMovementInputFactory.Input input() {
        return input;
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
        return movementResult.horizontalInputLimit() * INPUT_FRICTION;
    }

    @Override
    public PredVector stepCandidate() {
        return BedrockSimulation.stepCandidate(movementResult)
                .map(this::stepVector)
                .orElse(null);
    }

    @Override
    public PredVector stepCandidate(Vec3 end) {
        Vec3d previous = movementResult.previousState().physicalFeetPosition();
        Vec3d sourceMove = movementResult.rawPredictedPhysicalFeetPosition().subtract(previous);
        Vec3d requestedDelta = new Vec3d(end.x, sourceMove.y(), end.z);
        return BedrockSimulation.stepCandidate(movementResult, requestedDelta)

                .map(position -> stepVector(new Vec3d(
                        movementResult.rawPredictedPhysicalFeetPosition().x(),
                        position.y(),
                        movementResult.rawPredictedPhysicalFeetPosition().z())))
                .orElse(null);
    }

    @Override
    public StepCandidate stepCandidate(Vec3 end, CollideAxisData collisionData) {
        if (collisionData != null && !collisionData.getUnknown().isEmpty()) {
            return new StepCandidate(null, true);
        }
        return new StepCandidate(stepCandidate(end), false);
    }

    @Override
    public double maxUpStep(CultPlayer player) {
        return movementResult.maxUpStep();
    }

    @Override
    public Vec3 collisionStuckSpeedMultiplier(SimulationContext context) {
        return new Vec3(1.0D, 1.0D, 1.0D);
    }

    private PredVector stepVector(Vec3d position) {
        Vec3d previous = movementResult.previousState().physicalFeetPosition();
        Vec3 stepDelta = BedrockVectorAdapter.toJava(position.subtract(previous));
        return new PredVector(stepDelta, this, "bedrock step candidate");
    }

}
