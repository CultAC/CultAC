package ac.grim.grimac.checks.impl.prediction.pipeline.java;

import ac.grim.grimac.checks.impl.prediction.DesyncStatus;
import ac.grim.grimac.checks.impl.prediction.PredVector;
import ac.grim.grimac.checks.impl.prediction.PredictionCarry;
import ac.grim.grimac.checks.impl.prediction.PredictionCommit;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.SimulationContext;
import ac.grim.grimac.checks.impl.prediction.pipeline.MovementEngine;
import ac.grim.grimac.checks.impl.prediction.stage.MovementModifiers;
import ac.grim.grimac.checks.impl.prediction.stage.VelocityTransformer;
import ac.grim.grimac.checks.impl.prediction.stage.uncertainty.CollisionModifier;
import ac.grim.grimac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.grim.grimac.checks.impl.prediction.stage.world.WorldData;
import ac.grim.grimac.checks.impl.prediction.stage.world.WorldStageBuilder;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.CollideAxisData;
import ac.grim.grimac.utils.nmsutil.NextTickVelocityDeriver;
import java.util.List;
import java.util.Set;
import net.minecraft.world.phys.Vec3;

public final class JavaMovementEngine implements MovementEngine {
    public static final JavaMovementEngine INSTANCE = new JavaMovementEngine();

    private JavaMovementEngine() {
    }

    @Override
    public WorldData buildWorld(
            WorldStageBuilder builder,
            GrimPlayer player,
            SimulationContext context,
            PredictionResult lastPrediction,
            DesyncStatus lastOnGround
    ) {
        return builder.generateWorldData(player, context, lastPrediction, lastOnGround);
    }

    @Override
    public List<PredVector> applyModifiers(
            MovementModifiers modifiers,
            GrimPlayer player,
            Set<Vec3> startingVelocities,
            SimulationContext context,
            PredictionResult lastPrediction,
            boolean canTickSkip
    ) {
        return modifiers.applyModifers(player, startingVelocities, context, lastPrediction, canTickSkip);
    }

    @Override
    public List<PredVector> startingVelocities(
            VelocityTransformer transformer,
            GrimPlayer player,
            List<PredVector> input,
            SimulationContext context
    ) {
        return transformer.generateMovementVectors(player, input);
    }

    @Override
    public ValidMovements createValidMovements(
            PredVector initialStartingVelocity,
            GrimPlayer player,
            PredictionResult result,
            boolean canStep
    ) {
        return new ValidMovements(initialStartingVelocity, player, result, canStep);
    }

    @Override
    public CollideAxisData probeCollisions(
            CollisionModifier modifier,
            GrimPlayer player,
            SimulationContext context,
            double minY,
            Vec3 target,
            Vec3 playerPos,
            PredVector initialStartingVelocity,
            SimpleCollisionBox attemptedMovementExtents,
            boolean canStep
    ) {
        return modifier.probeCollisions(player, context, minY, target, playerPos, attemptedMovementExtents);
    }

    @Override
    public PredictionCommit commitNextTick(
            GrimPlayer player,
            PredictionResult result,
            PredictionResult lastPrediction,
            Vec3 acceptedDiff,
            PredictionCarry currentCarry
    ) {
        return new PredictionCommit(
                currentCarry,
                NextTickVelocityDeriver.findVelocitiesForNextTick(player, result, lastPrediction, acceptedDiff));
    }
}
