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
import ac.grim.grimac.network.protocol.ClientVersion;
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
        if (context.getProfileCarry() instanceof JavaPredictionCarry selected && !selected.states().isEmpty()
                && player.checkManager.getSimulationProcessor().getCurrentPredictionCommit().carry() instanceof JavaPredictionCarry committed
                && hasMatchingVelocity(committed, startingVelocities)) {
            var filtered = new java.util.HashSet<Vec3>();
            for (var state : selected.states()) if (startingVelocities.contains(state.velocity())) filtered.add(state.velocity());
            if (filtered.isEmpty()) return List.of();
            startingVelocities = filtered;
        }
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
        return modifier.probeCollisions(player, context, minY, target, playerPos, attemptedMovementExtents,
                new CollisionModifier.ProbeDimensions(0.6F, 0.6F, 1.8F, SimpleCollisionBox.AxisEpsilon.JAVA),
                JavaFallDistance.beforeMove(player, context, initialStartingVelocity), !canStep);
    }

    @Override
    public PredictionCommit commitNextTick(
            GrimPlayer player,
            PredictionResult result,
            PredictionResult lastPrediction,
            Vec3 acceptedDiff,
            PredictionCarry currentCarry
    ) {
        if (contextUsesExactEffects(result.getSimulationContext())) {
            var moved = JavaFallDistance.afterCollision(player, result, acceptedDiff);
            Set<Vec3> velocities = NextTickVelocityDeriver.beforeBlockEffects(player, result, lastPrediction, moved.movement());
            var states = new java.util.LinkedHashSet<JavaPredictionCarry.State>();
            for (Vec3 velocity : velocities) {
                var effects = JavaInsideBlockEffects.resolve(player, result, moved.movement(), moved.fallDistance(), velocity, moved.landed());
                effects = afterActorTick(player, result.getSimulationContext(), moved.movement(), effects);
                effects = ac.grim.grimac.utils.nmsutil.GeyserVelocity.applyToState(player, result,
                        result.getSimulationContext().getStart().add(moved.movement()), effects);
                states.add(new JavaPredictionCarry.State(effects.velocity(), effects.fallDistance(), effects.stuckSpeed()));
            }
            var stateList = List.copyOf(states);
            Vec3 required = stateList.isEmpty() ? null : stateList.getFirst().stuckSpeed();
            for (var state : states) if (!java.util.Objects.equals(required, state.stuckSpeed())) { required = null; break; }
            // Contexts for subsequent ticks consume the complete per-candidate state below.
            result.getSimulationContext().getWorldData().setStuckSpeed(new ac.grim.grimac.utils.data.StuckSpeedData(required, null));
            var actor = result.getSimulationContext().getVehicle() == null ? player.compensatedEntities.getSelf() : result.getSimulationContext().getVehicle();
            var carry = new JavaPredictionCarry(actor, stateList.isEmpty() ? moved.fallDistance() : stateList.getFirst().fallDistance(), stateList);
            var nextVelocities = new java.util.HashSet<Vec3>();
            for (var state : states) nextVelocities.add(state.velocity());
            return new PredictionCommit(carry, nextVelocities);
        }
        return new PredictionCommit(
                JavaFallDistance.commit(player, result, acceptedDiff),
                NextTickVelocityDeriver.findVelocitiesForNextTick(player, result, lastPrediction, acceptedDiff));
    }
    private static JavaInsideBlockEffects.State afterActorTick(GrimPlayer player, SimulationContext context,
                                                               Vec3 movement, JavaInsideBlockEffects.State state) {
        // Strider#tick -> floatStrider is AFTER LivingEntity's inside-block callbacks.
        if (context.getVehicle() != null && context.getVehicle().isStrider()
                && context.getWorldData().getInLava().determinePessimistically()) {
            Vec3 end = context.getStart().add(movement);
            boolean onSurface = ac.grim.grimac.utils.nmsutil.Above.isAbove(end.y)
                    && !player.compensatedWorld.getFluidStateAt(net.minecraft.core.BlockPos.containing(end).above()).is(net.minecraft.tags.FluidTags.LAVA);
            if (!onSurface) return new JavaInsideBlockEffects.State(state.fallDistance(),
                    state.velocity().scale(0.5).add(0, 0.05, 0), state.stuckSpeed());
        }
        return state;
    }

    private static boolean hasMatchingVelocity(JavaPredictionCarry carry, Set<Vec3> velocities) {
        for (var state : carry.states()) if (velocities.contains(state.velocity())) return true;
        return false;
    }

    public static boolean contextUsesExactEffects(SimulationContext context) {
        return context != null && supportsExactEffects(context.getVersion());
    }

    public static boolean supportsExactEffects(ClientVersion version) {
        // Both audited clients run the same ordered callbacks after travel and
        // landing, including powder's fall-distance-dependent inside shape.
        return version.isNewerThanOrEquals(ClientVersion.V_26_1);
    }
}
