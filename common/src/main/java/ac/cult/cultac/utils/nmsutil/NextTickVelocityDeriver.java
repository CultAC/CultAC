package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaFallDistance;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaInsideBlockEffects;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class NextTickVelocityDeriver {
    private NextTickVelocityDeriver() {}

    /**
     * One player's reusable derivation sets. Velocities derived with a scratch are valid only until
     * {@link #release()}; a nested take while it is taken gets a fresh one.
     */
    public static final class Scratch {
        private ReusableVelocitySet candidates = new ReusableVelocitySet();
        private ReusableVelocitySet collided = new ReusableVelocitySet();
        private ReusableVelocitySet transformed = new ReusableVelocitySet();
        private boolean taken;

        public Scratch take() {
            Scratch scratch = taken ? new Scratch() : this;
            scratch.taken = true;
            return scratch;
        }

        public void release() {
            candidates = recycle(candidates);
            collided = recycle(collided);
            transformed = recycle(transformed);
            taken = false;
        }

        private static ReusableVelocitySet recycle(ReusableVelocitySet set) {
            if (!set.reusable()) return new ReusableVelocitySet();
            set.clear();
            return set;
        }
    }

    /** The deltaMovement each simulated travel of {@code result} carries into the next client tick. */
    public static Set<Vec3> findVelocitiesForNextTick(
            CultPlayer player, PredictionResult result, PredictionResult lastPrediction, Scratch scratch) {
        return findVelocities(player, result, lastPrediction, true, scratch);
    }

    public static Set<Vec3> beforeBlockEffects(
            CultPlayer player, PredictionResult result, PredictionResult lastPrediction, Scratch scratch) {
        return findVelocities(player, result, lastPrediction, false, scratch);
    }

    private static Set<Vec3> findVelocities(
            CultPlayer player,
            PredictionResult result,
            PredictionResult lastPrediction,
            boolean geysers,
            Scratch scratch) {
        List<PredictionResult> realities = VelocityCandidates.nextTickVelocityRealities(player, result);
        if (realities.size() == 1) {
            return findVelocitiesForSingleReality(player, realities.getFirst(), lastPrediction, geysers, scratch);
        }
        Set<Vec3> velocities = new HashSet<>();
        for (int i = 0; i < realities.size(); i++) {
            velocities.addAll(findVelocitiesForSingleReality(player, realities.get(i), lastPrediction, geysers, null));
        }
        return velocities;
    }

    private static Set<Vec3> findVelocitiesForSingleReality(
            CultPlayer player,
            PredictionResult result,
            PredictionResult lastPrediction,
            boolean geysers,
            Scratch scratch) {
        Set<Vec3> collided = scratch == null ? new HashSet<>() : scratch.collided;
        Set<Vec3> candidates = VelocityCandidates.derive(
                result, lastPrediction, scratch == null ? new HashSet<>() : scratch.candidates, collided);
        return transformCandidatesForNextTick(
                player, result, candidates, collided, scratch == null ? new HashSet<>() : scratch.transformed, geysers);
    }

    private static int bubbleBranches(CultPlayer player, PredictionResult result) {
        return JavaBubbleColumnVelocity.hasPrecisionAlternatives(player, result.getSimulationContext()) ? 2 : 1;
    }

    private static Vec3 postTravelPoint(
            CultPlayer player, PredictionResult result, Vec3 point, boolean geysers, boolean preciseGate) {
        if (!JavaMovementEngine.contextUsesExactEffects(result.getSimulationContext())) {
            if (JavaInsideBlockEffects.carriesStepOn(player, result)
                    && BlockIds.is(result.getSimulationContext().getWorldData().getOnBlock(), BlockIds.SLIME_BLOCK)) {
                var context = result.getSimulationContext();
                var moved = JavaFallDistance.afterCollision(
                        player, result, context.getEnd().subtract(context.getStart()));
                point = JavaInsideBlockEffects.stepOn(player, result, moved, point);
            }
            point = JavaBubbleColumnVelocity.afterTravel(player, result, point, preciseGate);
        }
        if (geysers)
            point = GeyserVelocity.applyToResult(player, result, Set.of(point))
                    .iterator()
                    .next();
        return point;
    }

    /** {@code collided} holds the velocities whose Entity#move set horizontalCollision. */
    private static Set<Vec3> transformCandidatesForNextTick(
            CultPlayer player,
            PredictionResult result,
            Set<Vec3> candidates,
            Set<Vec3> collided,
            Set<Vec3> transformed,
            boolean geysers) {
        float blockSpeedFactor = result.getSimulationContext().isNoPhysics()
                ? 1.0F
                : BlockSpeedFactors.nextTickForResult(player, result);
        travel(player, result, candidates, false, blockSpeedFactor, transformed);
        travel(player, result, collided, true, blockSpeedFactor, transformed);
        return applyPostTravelVelocityEffects(player, result, transformed, geysers);
    }

    private static void travel(
            CultPlayer player,
            PredictionResult result,
            Set<Vec3> candidates,
            boolean horizontalCollision,
            float blockSpeedFactor,
            Set<Vec3> transformed) {
        List<Boolean> onGroundStates =
                result.getSimulationContext().getLastOnGround().getStates();
        for (Vec3 vel : candidates) {
            for (int i = 0; i < onGroundStates.size(); i++) {
                List<Vec3> travelled = TravelVelocityTransformer.transform(
                        player,
                        onGroundStates.get(i),
                        result.getSimulationContext().getStart(),
                        vel,
                        horizontalCollision,
                        result,
                        blockSpeedFactor,
                        false);
                for (int j = 0; j < travelled.size(); j++) {
                    transformed.add(travelled.get(j));
                }
            }
        }
    }

    private static Set<Vec3> applyPostTravelVelocityEffects(
            CultPlayer player, PredictionResult result, Set<Vec3> velocities, boolean geysers) {
        if (!geysers && JavaMovementEngine.contextUsesExactEffects(result.getSimulationContext())) return velocities;
        Set<Vec3> after = new HashSet<>(velocities.size());
        int branches = bubbleBranches(player, result);
        for (Vec3 velocity : velocities)
            for (int branch = 0; branch < branches; branch++)
                after.add(postTravelPoint(player, result, velocity, geysers, branch == 1));
        return after;
    }
}
