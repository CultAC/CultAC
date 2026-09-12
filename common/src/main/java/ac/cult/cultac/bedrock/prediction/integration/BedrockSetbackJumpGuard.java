package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockActorDimensions;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Bedrock geometry and tick boundaries for the shared runner's Java setback guard. */
public final class BedrockSetbackJumpGuard {
    private BedrockSetbackJumpGuard() {
    }

    public static boolean advancesTravel(PredictionResult result) {
        BedrockPredVector candidate = candidate(result);
        return candidate != null && candidate.input().actorMovementTick()
                && candidate.movementResult().travelActive();
    }

    public static boolean hasSupportAtStart(CultPlayer player, PredictionResult result) {
        BedrockPredVector candidate = candidate(result);
        if (candidate == null) {
            return false;
        }
        var input = candidate.input();
        PlayerDimensionsState dimensions = BedrockActorDimensions.committedMovementDimensions(
                input.previousState(), input.movementContext().playerDimensionsState(), input.inputFrame());
        Vec3 start = result.getSimulationContext().getStart();
        SimpleCollisionBox box = GetBoundingBox.getBoundingBoxFromPosAndSize(
                start.x, start.y, start.z, (float) dimensions.width(), (float) dimensions.height());
        // This is Java's narrow pre-movement support check, not a movement
        // candidate or a recomputation of the client's retained collision flags.
        // ClientBlockShapes supplies compensated Bedrock shapes to Collisions.
        return Collisions.collide(player, box, 0.0D, -1.0E-7D, 0.0D,
                List.of(Collisions.Axis.Y), false).y == 0.0D;
    }

    private static BedrockPredVector candidate(PredictionResult result) {
        PredVector initial = result == null ? null : result.getInitialStartingVel();
        return initial == null ? null : initial.firstInLineage(BedrockPredVector.class);
    }
}
