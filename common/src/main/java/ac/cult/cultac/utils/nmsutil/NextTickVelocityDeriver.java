package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

public final class NextTickVelocityDeriver {
    private NextTickVelocityDeriver() {
    }

    public static Set<Vec3> findVelocitiesForNextTick(CultPlayer player, PredictionResult result, PredictionResult lastPrediction, Vec3 diff) {
        return findVelocities(player, result, lastPrediction, diff, true);
    }

    public static Set<Vec3> beforeBlockEffects(CultPlayer player, PredictionResult result, PredictionResult lastPrediction, Vec3 diff) {
        return findVelocities(player, result, lastPrediction, diff, false);
    }

    private static Set<Vec3> findVelocities(CultPlayer player, PredictionResult result, PredictionResult lastPrediction, Vec3 diff, boolean geysers) {
        Set<Vec3> velocities = new HashSet<>();
        for (PredictionResult reality : VelocityCandidates.nextTickVelocityRealities(player, result)) {
            velocities.addAll(findVelocitiesForSingleReality(player, reality, lastPrediction, diff, geysers));
        }
        return velocities;
    }

    private static Set<Vec3> findVelocitiesForSingleReality(CultPlayer player, PredictionResult result, PredictionResult lastPrediction, Vec3 diff, boolean geysers) {
        VelocityCandidates.Candidates candidates = VelocityCandidates.derive(result, lastPrediction, diff);
        return transformCandidatesForNextTick(player, result, lastPrediction, candidates.velocities(), geysers);
    }

    private static Set<Vec3> transformCandidatesForNextTick(CultPlayer player, PredictionResult result, PredictionResult lastPrediction, Set<Vec3> candidates, boolean geysers) {
        float additionalBlockFriction = BlockSpeedFactors.nextTickForResult(player, result);

        Set<Vec3> transformed = new HashSet<>();
        for (Vec3 vel : candidates) {
            for (boolean onGround : result.getSimulationContext().getLastOnGround().getStates()) {
                transformed.addAll(TravelVelocityTransformer.transform(player, onGround, result.getSimulationContext().getStart(), vel, result, additionalBlockFriction));
            }
        }

        return applyPostTravelVelocityEffects(player, result, transformed, geysers);
    }

    private static Set<Vec3> applyPostTravelVelocityEffects(CultPlayer player,
                                                            PredictionResult result,
                                                            Set<Vec3> velocities, boolean geysers) {
        velocities = RiddenWaterFloatVelocity.apply(result, velocities);
        // The 26.2 geyser ticker runs after the entity tick, so it modifies the
        // carried deltaMovement directly rather than going through travel.
        if (geysers) velocities = GeyserVelocity.applyToResult(player, result, velocities);
        return velocities;
    }
}
