package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.latency.ClientBlockEntityPass;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientKeyInput;
import ac.cult.cultac.utils.nmsutil.VelocityCandidates;
import java.util.ArrayList;
import java.util.List;

/** Physical travel on a CLIENT_TICK_END whose sendPosition withheld its position. */
public final class JavaWithheldTravel {
    private JavaWithheldTravel() {}

    /**
     * A tick-end with no position can still have a small physical travel. Generate keyboard movements first,
     * then require the reported keys and sendPosition's distance from its last SENT position. Commit a position
     * only when those source candidates agree; reporting tolerance never becomes deltaMovement.
     */
    public static Vec3 uniquePosition(CultPlayer player, PredictionResult prediction, Vec3 sent, double threshold) {
        if (player.isBedrockMovement()
                || player.getClientVersion().isOlderThan(ClientVersion.V_1_21_2)
                || !VelocityCandidates.hasEngineTrace(prediction)) return null;
        var context = prediction.getSimulationContext();
        Vec3 before = prediction.getValidMovements().getClosestTrace().preCollisionMovement();
        double bound = threshold * threshold;
        var reported = player.packetStateData.knownInput;
        if (reported.forwardImpulse() == 0
                && reported.strafeImpulse() == 0
                && (before.lengthSqr() == 0 || before.lengthSqr() > bound)) return null;
        List<Vec3> keys = ClientKeyInput.travelInputs(player, context, prediction.getInitialStartingVel());
        if (keys.isEmpty()) return null;
        boolean movingInput = false;
        for (Vec3 key : keys) {
            movingInput |= key.lengthSqr() > 0;
        }
        // Ordinary grounded idle ticks need no additional replay. This also admits a tiny carried fall with no keys.
        if (!movingInput && (before.lengthSqr() == 0 || before.lengthSqr() > bound)) return null;
        Vec3 selected = null;
        var realities = new ArrayList<PredictionResult>();
        realities.add(prediction);
        for (var reality : prediction.getRealities()) if (reality != prediction) realities.add(reality);
        for (var reality : realities) {
            if (reality.getFlagSeverity() != 0 || !VelocityCandidates.hasEngineTrace(reality)) continue;
            var state = reality.getSimulationContext();
            var pass = state.getPassAfterTravel();
            if (pass == null) pass = new ClientBlockEntityPass(List.of(), player.compensatedWorld.clientGameTime());
            List<Vec3> legalKeys =
                    JavaTravelInputs.reportedReplayInputs(player, state, reality.getInitialStartingVel());
            for (Vec3 input : JavaTravelInputs.replayInputs(player, state, reality.getInitialStartingVel())) {
                for (var candidate : JavaPassAfterTravel.replay(
                        player, reality, reality.getValidMovements().getClosestTrace(), pass, input)) {
                    Vec3 end = candidate.result().state().position();
                    if (sent.distanceToSqr(end) > bound || !legalKeys.contains(input)) continue;
                    if (selected != null && !selected.equals(end)) return null;
                    selected = end;
                }
            }
        }
        return selected;
    }
}
