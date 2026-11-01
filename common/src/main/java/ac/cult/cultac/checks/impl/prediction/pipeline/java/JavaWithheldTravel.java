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
        Vec3[] ends = tableEnds(player, prediction, sent, threshold);
        return ends != null && ends[0] != null && ends[0].equals(ends[1]) ? ends[0] : null;
    }

    /**
     * The withheld travel's end for each trig table, {vanilla, fast}, when the candidates of each table agree but the
     * tables do not: Entity#getInputVector's rotation hides which table the client used until a report shows it, so
     * each end stays a position of its own. Null otherwise.
     */
    public static Vec3[] tablePositions(CultPlayer player, PredictionResult prediction, Vec3 sent, double threshold) {
        Vec3[] ends = tableEnds(player, prediction, sent, threshold);
        return ends != null && ends[0] != null && ends[1] != null && !ends[0].equals(ends[1]) ? ends : null;
    }

    /** The end each table's candidates agree on, {vanilla, fast}, or null when candidates of one table disagree. */
    private static Vec3[] tableEnds(CultPlayer player, PredictionResult prediction, Vec3 sent, double threshold) {
        if (player.isBedrockMovement()
                || player.getClientVersion().isOlderThan(ClientVersion.V_1_21_2)
                || !VelocityCandidates.hasEngineTrace(prediction)) return null;
        var context = prediction.getSimulationContext();
        Vec3 before = prediction.getValidMovements().getClosestTrace().preCollisionMovement();
        double bound = threshold * threshold;
        var reported = player.packetStateData.knownInput;
        // An idle tick at the last sent position leaves nothing to commit. After withheld travel the client is not
        // there: an idle tick ends where that travel did, which only the replay finds.
        boolean nothingToReplay = before.lengthSqr() > bound || before.lengthSqr() == 0 && startsAt(prediction, sent);
        if (reported.forwardImpulse() == 0 && reported.strafeImpulse() == 0 && nothingToReplay) return null;
        List<Vec3> keys = ClientKeyInput.travelInputs(player, context, prediction.getInitialStartingVel());
        if (keys.isEmpty()) return null;
        boolean movingInput = false;
        for (Vec3 key : keys) {
            movingInput |= key.lengthSqr() > 0;
        }
        // Ordinary grounded idle ticks need no additional replay. This also admits a tiny carried fall with no keys.
        if (!movingInput && nothingToReplay) return null;
        Vec3[] ends = new Vec3[2];
        var realities = new ArrayList<PredictionResult>();
        realities.add(prediction);
        for (var reality : prediction.getRealities()) if (reality != prediction) realities.add(reality);
        for (var reality : realities) {
            if (reality.getFlagSeverity() != 0 || !VelocityCandidates.hasEngineTrace(reality)) continue;
            var state = reality.getSimulationContext();
            // Only a travel the client could have made places it; a check's candidate leaves no hidden position.
            if (!JavaTravelInputs.travelsReportedInput(player, state, reality.getInitialStartingVel())) continue;
            var pass = state.getPassAfterTravel();
            if (pass == null) pass = new ClientBlockEntityPass(List.of(), player.compensatedWorld.clientGameTime());
            var math = reality.getInitialStartingVel().firstInLineage(JavaMovementMath.class);
            List<Vec3> legalKeys =
                    JavaTravelInputs.reportedReplayInputs(player, state, reality.getInitialStartingVel());
            for (Vec3 input : JavaTravelInputs.replayInputs(player, state, reality.getInitialStartingVel())) {
                for (var candidate : JavaPassAfterTravel.replay(
                        player, reality, reality.getValidMovements().getClosestTrace(), pass, input)) {
                    Vec3 end = candidate.result().state().position();
                    if (sent.distanceToSqr(end) > bound || !legalKeys.contains(input)) continue;
                    for (int table = 0; table < 2; table++) {
                        if (math != null && math.vanilla() != (table == 0)) continue;
                        if (ends[table] != null && !ends[table].equals(end)) return null;
                        ends[table] = end;
                    }
                }
            }
        }
        // A table no candidate reached travels like the other.
        if (ends[0] == null) ends[0] = ends[1];
        if (ends[1] == null) ends[1] = ends[0];
        return ends;
    }

    /** Whether every accepted candidate's travel starts at {@code sent}, LocalPlayer#sendPosition's last position. */
    private static boolean startsAt(PredictionResult prediction, Vec3 sent) {
        if (!sent.equals(prediction.getSimulationContext().getStart())) return false;
        for (var reality : prediction.getRealities()) {
            if (reality.getFlagSeverity() == 0
                    && reality.getSimulationContext() != null
                    && !sent.equals(reality.getSimulationContext().getStart())) return false;
        }
        return true;
    }
}
