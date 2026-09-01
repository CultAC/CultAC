package ac.grim.grimac.checks.impl.prediction.pipeline;

import ac.grim.grimac.checks.impl.prediction.AuthoredMovementFrame;
import ac.grim.grimac.checks.impl.prediction.DesyncStatus;
import ac.grim.grimac.checks.impl.prediction.PredVector;
import ac.grim.grimac.checks.impl.prediction.PredictionCarry;
import ac.grim.grimac.checks.impl.prediction.PredictionCommit;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.PredictionSetbackState;
import ac.grim.grimac.checks.impl.prediction.SimulationContext;
import ac.grim.grimac.checks.impl.prediction.stage.MovementModifiers;
import ac.grim.grimac.checks.impl.prediction.stage.VelocityTransformer;
import ac.grim.grimac.checks.impl.prediction.stage.uncertainty.CollisionModifier;
import ac.grim.grimac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.grim.grimac.checks.impl.prediction.stage.world.WorldData;
import ac.grim.grimac.checks.impl.prediction.stage.world.WorldStageBuilder;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.CollideAxisData;
import ac.grim.grimac.utils.data.TeleportData;
import java.util.List;
import java.util.Set;
import net.minecraft.world.phys.Vec3;

public interface MovementEngine {
    WorldData buildWorld(
            WorldStageBuilder builder,
            GrimPlayer player,
            SimulationContext context,
            PredictionResult lastPrediction,
            DesyncStatus lastOnGround
    );

    List<PredVector> applyModifiers(
            MovementModifiers modifiers,
            GrimPlayer player,
            Set<Vec3> startingVelocities,
            SimulationContext context,
            PredictionResult lastPrediction,
            boolean canTickSkip
    );

    List<PredVector> startingVelocities(
            VelocityTransformer transformer,
            GrimPlayer player,
            List<PredVector> input,
            SimulationContext context
    );

    ValidMovements createValidMovements(
            PredVector initialStartingVelocity,
            GrimPlayer player,
            PredictionResult result,
            boolean canStep
    );

    CollideAxisData probeCollisions(
            CollisionModifier modifier,
            GrimPlayer player,
            SimulationContext context,
            double minY,
            Vec3 target,
            Vec3 playerPos,
            PredVector initialStartingVelocity,
            SimpleCollisionBox attemptedMovementExtents,
            boolean canStep
    );

    default void evaluateCandidate(GrimPlayer player, PredictionResult result) {
    }

    default boolean isBetterCandidate(PredictionResult candidate, PredictionResult currentBest) {
        return candidate.isBetterThan(currentBest);
    }

    PredictionCommit commitNextTick(
            GrimPlayer player,
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
    default PredictionSetbackState prepareSetbackState(GrimPlayer player, PredictionResult result) {
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
            GrimPlayer player,
            PredictionCarry carry,
            AuthoredMovementFrame authoredMovementFrame,
            SimulationContext context
    ) {
        return null;
    }
}
