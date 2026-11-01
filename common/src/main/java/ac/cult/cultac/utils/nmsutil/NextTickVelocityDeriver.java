package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.ShulkerBoxShoveResult;
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
        private ReusableVelocitySet transformed = new ReusableVelocitySet();
        private boolean taken;

        public Scratch take() {
            Scratch scratch = taken ? new Scratch() : this;
            scratch.taken = true;
            return scratch;
        }

        public void release() {
            candidates = recycle(candidates);
            transformed = recycle(transformed);
            taken = false;
        }

        private static ReusableVelocitySet recycle(ReusableVelocitySet set) {
            if (!set.reusable()) return new ReusableVelocitySet();
            set.clear();
            return set;
        }
    }

    public static Set<Vec3> findVelocitiesForNextTick(
            CultPlayer player, PredictionResult result, PredictionResult lastPrediction, Vec3 diff, Scratch scratch) {
        return findVelocities(player, result, lastPrediction, diff, true, scratch);
    }

    public static Set<Vec3> beforeBlockEffects(
            CultPlayer player, PredictionResult result, PredictionResult lastPrediction, Vec3 diff, Scratch scratch) {
        return findVelocities(player, result, lastPrediction, diff, false, scratch);
    }

    private static Set<Vec3> findVelocities(
            CultPlayer player,
            PredictionResult result,
            PredictionResult lastPrediction,
            Vec3 diff,
            boolean geysers,
            Scratch scratch) {
        List<PredictionResult> realities = VelocityCandidates.nextTickVelocityRealities(player, result);
        if (realities.size() == 1) {
            return findVelocitiesForSingleReality(player, realities.getFirst(), lastPrediction, diff, geysers, scratch);
        }
        Set<Vec3> velocities = new HashSet<>();
        for (int i = 0; i < realities.size(); i++) {
            velocities.addAll(
                    findVelocitiesForSingleReality(player, realities.get(i), lastPrediction, diff, geysers, null));
        }
        return velocities;
    }

    private static Set<Vec3> findVelocitiesForSingleReality(
            CultPlayer player,
            PredictionResult result,
            PredictionResult lastPrediction,
            Vec3 diff,
            boolean geysers,
            Scratch scratch) {
        Set<Vec3> candidates = VelocityCandidates.derive(
                result, lastPrediction, diff, scratch == null ? new HashSet<>() : scratch.candidates);
        return transformCandidatesForNextTick(
                player, result, candidates, scratch == null ? new HashSet<>() : scratch.transformed, geysers);
    }

    private static Set<Vec3> transformCandidatesForNextTick(
            CultPlayer player, PredictionResult result, Set<Vec3> candidates, Set<Vec3> transformed, boolean geysers) {
        float additionalBlockFriction = BlockSpeedFactors.nextTickForResult(player, result);

        List<Boolean> onGroundStates =
                result.getSimulationContext().getLastOnGround().getStates();
        for (Vec3 vel : candidates) {
            for (int i = 0; i < onGroundStates.size(); i++) {
                List<Vec3> travelled = TravelVelocityTransformer.transform(
                        player,
                        onGroundStates.get(i),
                        result.getSimulationContext().getStart(),
                        vel,
                        result,
                        additionalBlockFriction);
                for (int j = 0; j < travelled.size(); j++) {
                    transformed.add(travelled.get(j));
                }
            }
        }

        return applyPostTravelVelocityEffects(player, result, transformed, geysers);
    }

    private static Set<Vec3> applyPostTravelVelocityEffects(
            CultPlayer player, PredictionResult result, Set<Vec3> velocities, boolean geysers) {
        velocities = RiddenWaterFloatVelocity.apply(result, velocities);
        // The 26.2 geyser ticker runs after the entity tick, so it modifies the
        // carried deltaMovement directly rather than going through travel.
        if (geysers) velocities = GeyserVelocity.applyToResult(player, result, velocities);
        // From 26.3 the shulker box block shoves after the travel are block entity ticks too.
        ShulkerBoxShoveResult shove = geysers ? JavaMovementEngine.shulkerBoxShoveAfterTravel(result) : null;
        if (shove != null) {
            Set<Vec3> shoved = new HashSet<>(velocities.size());
            for (Vec3 velocity : velocities) shoved.add(shove.velocityAfter(velocity));
            velocities = shoved;
        }
        return velocities;
    }
}
