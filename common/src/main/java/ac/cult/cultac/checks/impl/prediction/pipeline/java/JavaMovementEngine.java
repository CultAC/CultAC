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
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldStageBuilder;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.data.PistonPushes;
import ac.cult.cultac.utils.data.ShulkerBoxShoveResult;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BlockProperties;
import ac.cult.cultac.utils.nmsutil.GeyserVelocity;
import ac.cult.cultac.utils.nmsutil.NextTickVelocityDeriver;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
            if (filtered.isEmpty()) return new ArrayList<>();
            startingVelocities = filtered;
        }
        // Before 26.3 the shulker box block shoves run between the last travel and this one.
        PistonPushes pushes =
                context.getWorldData() == null ? null : context.getWorldData().getPistonPushes();
        ShulkerBoxShoveResult shove = pushes == null ? null : pushes.getShulkerBoxShoveResult();
        if (shove != null) {
            Set<Vec3> shoved = new LinkedHashSet<>(startingVelocities.size());
            for (Vec3 velocity : startingVelocities) shoved.add(shove.velocityAfter(velocity));
            startingVelocities = shoved;
        }
        return modifiers.applyModifers(player, startingVelocities, context, lastPrediction, canTickSkip);
    }

    /** What the shulker box block shoves after this travel leave, from 26.3, when any reaches the player, else null. */
    public static ShulkerBoxShoveResult shulkerBoxShoveAfterTravel(PredictionResult result) {
        if (result.getValidMovements() == null) return null;
        MovementTrace trace = result.getValidMovements().getClosestTrace();
        return trace == null ? null : trace.shulkerBoxShove();
    }

    @Override
    public List<PredVector> startingVelocities(
            VelocityTransformer transformer, CultPlayer player, List<PredVector> input, SimulationContext context) {
        return transformer.generateMovementVectors(player, input);
    }

    /**
     * A clean candidate from the carried velocity beats a clean tick-skip start. Of two equally flagged candidates from
     * the same start (tick skip and packet velocities), the one nearer the client's move is the movement it ran; across
     * other starts the start-velocity sort order stands.
     */
    @Override
    public boolean isBetterCandidate(PredictionResult candidate, PredictionResult currentBest) {
        // A tick end proves LocalPlayer#tick ran each unsent tick, so the carried velocity is the client's
        // start; its Entity#move collisions drive SlimeBlock#bounceUp, SlimeBlock#stepOn and Entity#onGround.
        if (currentBest != null
                && candidate.getFlagSeverity() == 0.0
                && currentBest.getFlagSeverity() == 0.0
                && currentBest.getInitialStartingVel().isTickSkip()
                && !candidate.getInitialStartingVel().isTickSkip()) {
            return true;
        }
        if (currentBest == null
                || candidate.getFlagSeverity() != currentBest.getFlagSeverity()
                || !candidate.getInitialStartingVel().hasSameStartAs(currentBest.getInitialStartingVel())) {
            return candidate.isBetterThan(currentBest);
        }
        return candidate.selectionOffset() < currentBest.selectionOffset();
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
                !canStep,
                context.getHorizontalInputRadius(player, initialStartingVelocity));
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
        if (result.getSimulationContext().getVehicle() != null)
            settleRiddenSupport(player, result, JavaFallDistance.movement(result, acceptedDiff));
        if (contextUsesExactEffects(result.getSimulationContext())) {
            var moved = JavaFallDistance.afterCollision(player, result, acceptedDiff);
            result.setJavaCommittedMove(moved);
            Set<Vec3> velocities = NextTickVelocityDeriver.beforeBlockEffects(
                    player, result, lastPrediction, moved.movement(), scratch);
            JavaInsideBlockEffects.Scan scan = JavaInsideBlockEffects.scan(player, result, moved.movement());
            Vec3 end = result.getSimulationContext().getStart().add(moved.movement());
            var states = new ArrayList<JavaPredictionCarry.State>(velocities.size());
            var nextVelocities = new LinkedHashSet<Vec3>(velocities.size() * 2);
            for (Vec3 velocity : velocities) {
                Vec3 stepped = JavaInsideBlockEffects.stepOn(player, result, moved, velocity);
                var effects = JavaInsideBlockEffects.resolve(
                        player, result, scan, moved.fallDistance(), stepped, moved.landed());
                effects = afterActorTick(player, result.getSimulationContext(), moved.movement(), effects);
                effects = GeyserVelocity.applyToState(player, result, end, effects);
                ShulkerBoxShoveResult shove = shulkerBoxShoveAfterTravel(result);
                if (shove != null)
                    effects = new JavaInsideBlockEffects.State(
                            effects.fallDistance(), shove.velocityAfter(effects.velocity()), effects.stuckSpeed());
                var state =
                        new JavaPredictionCarry.State(effects.velocity(), effects.fallDistance(), effects.stuckSpeed());
                if (!states.contains(state)) {
                    states.add(state);
                    nextVelocities.add(state.velocity());
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
            return new PredictionCommit(carry, nextVelocities);
        }
        return new PredictionCommit(
                JavaFallDistance.commit(player, result, acceptedDiff),
                NextTickVelocityDeriver.findVelocitiesForNextTick(
                        player, result, lastPrediction, acceptedDiff, scratch));
    }

    /**
     * Entity#move's setOnGroundWithMovement on the root vehicle: checkSupportingBlock runs on the move's own
     * verticalCollisionBelow, set when collision changes a downward move's y (a landing or a step up), and
     * getBlockSpeedFactor, the effect block and the next travel's friction read the supporting block it stores. The
     * world data's support, built before the move, holds the vehicle's starting ground.
     */
    private static void settleRiddenSupport(CultPlayer player, PredictionResult result, Vec3 movement) {
        SimulationContext context = result.getSimulationContext();
        double requestedY = result.getInitialStartingVel().y * context.getLastStuckSpeed().y;
        boolean collidedBelow = requestedY < 0.0 && !JavaFallDistance.sameMoveY(movement.y, requestedY);
        MainSupportingBlockData support = player.checkManager
                .getSimulationProcessor()
                .mainSupportingBlockAfter(
                        context.getToMaxPose(), context.getEnd().subtract(context.getStart()), collidedBelow);
        WorldData world = context.getWorldData();
        world.setMainSupportingBlockPos(support);
        world.setOnBlock(BlockProperties.getOnPos(player, support, context.getEnd()));
    }

    /**
     * {@code carry} plus, for each state, the stuck state that {@code last}'s move resolves against the current
     * world: LivingEntity#aiStep's applyEffectsFromBlocks sets Entity#stuckSpeedMultiplier from the world the client
     * has when it ends that tick, which can hold a block update the model applies only after it.
     */
    public static JavaPredictionCarry withStuckStatesFromCurrentWorld(
            CultPlayer player, PredictionResult last, JavaPredictionCarry carry) {
        JavaFallDistance.Moved moved = last.getJavaCommittedMove();
        if (moved == null) return carry;
        JavaInsideBlockEffects.Scan scan = JavaInsideBlockEffects.scan(player, last, moved.movement());
        if (scan == null) return carry;
        List<JavaPredictionCarry.State> states = new ArrayList<>(carry.states());
        for (JavaPredictionCarry.State state : carry.states()) {
            var effects = JavaInsideBlockEffects.resolve(
                    player, last, scan, moved.fallDistance(), state.velocity(), moved.landed());
            if (effects.stuckSpeed() == null || effects.stuckSpeed().equals(state.stuckSpeed())) continue;
            var stuck = new JavaPredictionCarry.State(state.velocity(), effects.fallDistance(), effects.stuckSpeed());
            if (!states.contains(stuck)) states.add(stuck);
        }
        return states.size() == carry.states().size()
                ? carry
                : new JavaPredictionCarry(carry.actor(), carry.fallDistance(), states);
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
