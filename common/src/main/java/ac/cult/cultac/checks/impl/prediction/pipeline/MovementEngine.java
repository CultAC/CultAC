package ac.cult.cultac.checks.impl.prediction.pipeline;

import ac.cult.cultac.checks.impl.prediction.AuthoredMovementFrame;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionCarry;
import ac.cult.cultac.checks.impl.prediction.PredictionCommit;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.PredictionSetbackState;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.MovementModifiers;
import ac.cult.cultac.checks.impl.prediction.stage.VelocityTransformer;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CollisionModifier;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldStageBuilder;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.data.TeleportData;
import java.util.List;
import java.util.Set;
import net.minecraft.world.phys.Vec3;

public interface MovementEngine {
    WorldData buildWorld(
            WorldStageBuilder builder,
            CultPlayer player,
            SimulationContext context,
            PredictionResult lastPrediction,
            DesyncStatus lastOnGround
    );

    List<PredVector> applyModifiers(
            MovementModifiers modifiers,
            CultPlayer player,
            Set<Vec3> startingVelocities,
            SimulationContext context,
            PredictionResult lastPrediction,
            boolean canTickSkip
    );

    List<PredVector> startingVelocities(
            VelocityTransformer transformer,
            CultPlayer player,
            List<PredVector> input,
            SimulationContext context
    );

    ValidMovements createValidMovements(
            PredVector initialStartingVelocity,
            CultPlayer player,
            PredictionResult result,
            boolean canStep
    );

    CollideAxisData probeCollisions(
            CollisionModifier modifier,
            CultPlayer player,
            SimulationContext context,
            double minY,
            Vec3 target,
            Vec3 playerPos,
            PredVector initialStartingVelocity,
            SimpleCollisionBox attemptedMovementExtents,
            boolean canStep
    );

    default void evaluateCandidate(CultPlayer player, PredictionResult result) {
    }

    default boolean isBetterCandidate(PredictionResult candidate, PredictionResult currentBest) {
        return candidate.isBetterThan(currentBest);
    }

    PredictionCommit commitNextTick(
            CultPlayer player,
            PredictionResult result,
            PredictionResult lastPrediction,
            Vec3 acceptedDiff,
            PredictionCarry currentCarry
    );

    /**
     * Captures the engine-derived end-of-tick state used when the current
     * packet endpoint is rejected and a setback is requested by a
     * post-prediction listener. This must not reconcile from the rejected
     * acceptedDiff.
     */
    default PredictionSetbackState prepareSetbackState(CultPlayer player, PredictionResult result) {
        return null;
    }

    default PredictionSetbackState captureSetbackState(PredictionCommit commit) {
        return null;
    }

    default PredictionCommit applyTeleportToCarry(
            PredictionCarry carry,
            TeleportData teleport
    ) {
        return null;
    }

    default PredictionCarry applyAcknowledgedGlidingToCarry(
            PredictionCarry carry,
            boolean gliding
    ) {
        return carry;
    }

    /** Applies an immobile tick without accepting the packet position. */
    default PredictionCommit applyImmobileStateToCarry(
            CultPlayer player,
            PredictionCarry carry,
            AuthoredMovementFrame authoredMovementFrame,
            SimulationContext context
    ) {
        return null;
    }
}
