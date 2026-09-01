package ac.grim.grimac.utils.nmsutil;

import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

public final class NextTickVelocityDeriver {
    private NextTickVelocityDeriver() {
    }

    public static Set<Vec3> findVelocitiesForNextTick(GrimPlayer player, PredictionResult result, PredictionResult lastPrediction, Vec3 diff) {
        Set<Vec3> velocities = new HashSet<>();
        for (PredictionResult reality : VelocityCandidates.nextTickVelocityRealities(player, result)) {
            velocities.addAll(findVelocitiesForSingleReality(player, reality, lastPrediction, diff));
        }
        return velocities;
    }

    private static Set<Vec3> findVelocitiesForSingleReality(GrimPlayer player, PredictionResult result, PredictionResult lastPrediction, Vec3 diff) {
        VelocityCandidates.Candidates candidates = VelocityCandidates.derive(result, lastPrediction, diff);
        return transformCandidatesForNextTick(player, result, lastPrediction, candidates.velocities());
    }

    private static Set<Vec3> transformCandidatesForNextTick(GrimPlayer player, PredictionResult result, PredictionResult lastPrediction, Set<Vec3> candidates) {
        float additionalBlockFriction = BlockSpeedFactors.nextTickForResult(player, result);

        Set<Vec3> transformed = new HashSet<>();
        for (Vec3 vel : candidates) {
            for (boolean onGround : result.getSimulationContext().getLastOnGround().getStates()) {
                transformed.addAll(TravelVelocityTransformer.transform(player, onGround, result.getSimulationContext().getStart(), vel, result, additionalBlockFriction));
            }
        }

        return applyPostTravelVelocityEffects(player, result, transformed);
    }

    private static Set<Vec3> applyPostTravelVelocityEffects(GrimPlayer player,
                                                            PredictionResult result,
                                                            Set<Vec3> velocities) {
        velocities = RiddenWaterFloatVelocity.apply(result, velocities);
        // The 26.2 geyser ticker runs after the entity tick, so it modifies the
        // carried deltaMovement directly rather than going through travel.
        velocities = GeyserVelocity.applyToResult(player, result, velocities);
        return velocities;
    }
}
