package ac.grim.grimac.bedrock.prediction;

import ac.grim.grimac.bedrock.prediction.integration.BedrockNextTickStates;
import ac.grim.grimac.checks.impl.prediction.PredictionCommit;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/**
 * Chooses which already-legal end-of-tick result stock Geyser should cache.
 * The client report ranks candidates only; it never expands the legal set.
 */
public final class BedrockTrustedEndOfTickVelocity {
    private BedrockTrustedEndOfTickVelocity() {
    }

    public static Vec3 select(PredictionCommit commit, Vec3 reported) {
        if (commit == null) {
            return null;
        }
        List<Vec3> candidates = new ArrayList<>(commit.startingVelocities());
        if (candidates.isEmpty()
                && commit.carry() instanceof BedrockNextTickStates carry
                && !carry.profileEntries().isEmpty()) {
            var velocity = carry.profileEntries().getFirst().state().velocity();
            return toFloatVector(velocity.x(), velocity.y(), velocity.z());
        }
        return select(candidates, reported);
    }

    public static Vec3 select(List<Vec3> candidates, Vec3 reported) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        Vec3 best = toFloatVector(candidates.getFirst());
        if (!isFinite(reported)) {
            return best;
        }
        Vec3 quantizedReport = toFloatVector(reported);
        double bestDistance = best.distanceToSqr(quantizedReport);
        for (int i = 1; i < candidates.size(); i++) {
            Vec3 candidate = toFloatVector(candidates.get(i));
            double distance = candidate.distanceToSqr(quantizedReport);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static Vec3 toFloatVector(Vec3 vector) {
        return toFloatVector(vector.x, vector.y, vector.z);
    }

    private static Vec3 toFloatVector(double x, double y, double z) {
        return new Vec3((float) x, (float) y, (float) z);
    }

    private static boolean isFinite(Vec3 vector) {
        return vector != null
                && Double.isFinite(vector.x)
                && Double.isFinite(vector.y)
                && Double.isFinite(vector.z);
    }
}
