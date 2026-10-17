package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionCarry;
import ac.cult.cultac.checks.impl.prediction.PredictionCommit;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.pipeline.MovementEngine;
import ac.cult.cultac.checks.impl.prediction.stage.MovementModifiers;
import ac.cult.cultac.checks.impl.prediction.stage.VelocityTransformer;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CollisionModifier;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldStageBuilder;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GeyserVelocity;
import ac.cult.cultac.utils.nmsutil.NextTickVelocityDeriver;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class JavaMovementEngine implements MovementEngine {
    public static final JavaMovementEngine INSTANCE = new JavaMovementEngine();
    private static final CollisionModifier.ProbeDimensions PROBE_DIMENSIONS =
            new CollisionModifier.ProbeDimensions(0.6F, 0.6F, 1.8F, SimpleCollisionBox.AxisEpsilon.JAVA);

    private JavaMovementEngine() {}

    @Override
    public WorldData buildWorld(
            WorldStageBuilder builder,
            CultPlayer player,
            SimulationContext context,
            PredictionResult lastPrediction,
            DesyncStatus lastOnGround) {
        return builder.generateWorldData(player, context, lastPrediction, lastOnGround);
    }

    @Override
    public List<PredVector> applyModifiers(
            MovementModifiers modifiers,
            CultPlayer player,
            Set<Vec3> startingVelocities,
            SimulationContext context,
            PredictionResult lastPrediction,
            boolean canTickSkip) {
        if (context.getProfileCarry() instanceof JavaPredictionCarry selected
                && !selected.states().isEmpty()
                && player.checkManager.getSimulationProcessor().getCurrentPredictionCarry()
                        instanceof JavaPredictionCarry committed
                && hasMatchingVelocity(committed, startingVelocities)) {
            var filtered = new java.util.HashSet<Vec3>();
            for (var state : selected.states())
                if (startingVelocities.contains(state.velocity())) filtered.add(state.velocity());
            if (filtered.isEmpty()) return List.of();
            startingVelocities = filtered;
        }
        return modifiers.applyModifers(player, startingVelocities, context, lastPrediction, canTickSkip);
    }

    @Override
    public List<PredVector> startingVelocities(
            VelocityTransformer transformer, CultPlayer player, List<PredVector> input, SimulationContext context) {
        return transformer.generateMovementVectors(player, input);
    }

    @Override
    public ValidMovements createValidMovements(
            PredVector initialStartingVelocity, CultPlayer player, PredictionResult result, boolean canStep) {
        return new ValidMovements(initialStartingVelocity, player, result, canStep);
    }

    @Override
    public CollideAxisData probeCollisions(
            CollisionModifier modifier,
            CultPlayer player,
            SimulationContext context,
            double minY,
            Vec3 target,
            Vec3 playerPos,
            PredVector initialStartingVelocity,
            SimpleCollisionBox attemptedMovementExtents,
            boolean canStep) {
        return modifier.probeCollisions(
                player,
                context,
                minY,
                target,
                playerPos,
                attemptedMovementExtents,
                context.getScale() == 1.0F || context.getVehicle() != null
                        ? PROBE_DIMENSIONS
                        : new CollisionModifier.ProbeDimensions(
                                context.getMaxWidth(),
                                context.getMinHeight(),
                                context.getMaxHeight(),
                                SimpleCollisionBox.AxisEpsilon.JAVA),
                JavaFallDistance.beforeMove(player, context, initialStartingVelocity),
                !canStep);
    }

    @Override
    public PredictionCommit commitNextTick(
            CultPlayer player,
            PredictionResult result,
            PredictionResult lastPrediction,
            Vec3 acceptedDiff,
            PredictionCarry currentCarry) {
        NextTickVelocityDeriver.Scratch scratch =
                player.checkManager.getSimulationProcessor().velocityScratch().take();
        try {
            return commitNextTick(player, result, lastPrediction, acceptedDiff, scratch);
        } finally {
            scratch.release();
        }
    }

    private static PredictionCommit commitNextTick(
            CultPlayer player,
            PredictionResult result,
            PredictionResult lastPrediction,
            Vec3 acceptedDiff,
            NextTickVelocityDeriver.Scratch scratch) {
        if (contextUsesExactEffects(result.getSimulationContext())) {
            var moved = JavaFallDistance.afterCollision(player, result, acceptedDiff);
            Set<Vec3> velocities = NextTickVelocityDeriver.beforeBlockEffects(
                    player, result, lastPrediction, moved.movement(), scratch);
            JavaInsideBlockEffects.Scan scan = JavaInsideBlockEffects.scan(player, result, moved.movement());
            Vec3 end = result.getSimulationContext().getStart().add(moved.movement());
            var states = new ArrayList<JavaPredictionCarry.State>(velocities.size());
            var nextVelocities = new ArrayList<Vec3>(velocities.size());
            for (Vec3 velocity : velocities) {
                var effects = JavaInsideBlockEffects.resolve(
                        player, result, scan, moved.fallDistance(), velocity, moved.landed());
                effects = afterActorTick(player, result.getSimulationContext(), moved.movement(), effects);
                effects = GeyserVelocity.applyToState(player, result, end, effects);
                var state =
                        new JavaPredictionCarry.State(effects.velocity(), effects.fallDistance(), effects.stuckSpeed());
                if (!states.contains(state)) {
                    states.add(state);
                    if (!nextVelocities.contains(state.velocity())) nextVelocities.add(state.velocity());
                }
            }
            var stateList = List.copyOf(states);
            Vec3 required = stateList.isEmpty() ? null : stateList.getFirst().stuckSpeed();
            for (int i = 0; i < stateList.size(); i++)
                if (!java.util.Objects.equals(required, stateList.get(i).stuckSpeed())) {
                    required = null;
                    break;
                }
            // Contexts for subsequent ticks consume the complete per-candidate state below.
            result.getSimulationContext()
                    .getWorldData()
                    .setStuckSpeed(new ac.cult.cultac.utils.data.StuckSpeedData(required, null));
            var actor = result.getSimulationContext().getVehicle() == null
                    ? player.compensatedEntities.getSelf()
                    : result.getSimulationContext().getVehicle();
            var carry = new JavaPredictionCarry(
                    actor,
                    stateList.isEmpty()
                            ? moved.fallDistance()
                            : stateList.getFirst().fallDistance(),
                    stateList);
            return new PredictionCommit(carry, Set.of(nextVelocities.toArray(Vec3[]::new)));
        }
        return new PredictionCommit(
                JavaFallDistance.commit(player, result, acceptedDiff),
                NextTickVelocityDeriver.findVelocitiesForNextTick(
                        player, result, lastPrediction, acceptedDiff, scratch));
    }

    private static JavaInsideBlockEffects.State afterActorTick(
            CultPlayer player, SimulationContext context, Vec3 movement, JavaInsideBlockEffects.State state) {
        // Strider#tick -> floatStrider is AFTER LivingEntity's inside-block callbacks.
        if (context.getVehicle() != null
                && context.getVehicle().isStrider()
                && context.getWorldData().getInLava().determinePessimistically()) {
            Vec3 end = context.getStart().add(movement);
            boolean onSurface = ac.cult.cultac.utils.nmsutil.Above.isAbove(end.y)
                    && !ac.cult.blocksim.data.FluidTags.LAVA.test(
                            ac.cult.cultac.utils.nmsutil.ClientFluidQueries.fluidAt(
                                    player.compensatedWorld,
                                    ac.cult.cultac.protocol.value.BlockPos.containing(end.x, end.y, end.z)
                                            .above()));
            if (!onSurface)
                return new JavaInsideBlockEffects.State(
                        state.fallDistance(), state.velocity().scale(0.5).add(0, 0.05, 0), state.stuckSpeed());
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
        // 1.21.11 Entity#checkInsideBlocks already uses the same axis-ordered,
        // step-based callbacks after travel/landing as 26.1 and 26.2. In particular,
        // powder snow's inside shape must observe the fall distance at that step.
        return version.isNewerThanOrEquals(ClientVersion.V_1_21_11);
    }
}
