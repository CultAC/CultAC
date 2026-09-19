package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.integration.BedrockProfileState.Entry;
import ac.cult.cultac.bedrock.protocol.BedrockMovementCorrection;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionCommit;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.player.CultPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Outgoing corrections replay the correction history; outbound updates can change the continuing model. */
final class BedrockMovementRewind {
    private final BedrockBlockCollisionWorldSampler worlds = new BedrockBlockCollisionWorldSampler();
    private final BedrockActorHistory authoritative = new BedrockActorHistory();
    private BedrockActorHistory correctionHistory;
    private final List<Update> updates = new ArrayList<>();
    private boolean initialUpdates;

    void clear() {
        authoritative.clear();
        correctionHistory = null;
        updates.clear();
        initialUpdates = false;
    }

    void queue(long tick, boolean historical, BedrockReplayEvent event) {
        updates.add(new Update(tick, historical, event));
    }

    PredictionCommit apply(CultPlayer player, PredictionCommit current) {
        if (updates.isEmpty()) return current;
        List<Entry> entries = BedrockProfileState.profileEntries(current.carry());
        if (entries.isEmpty()) { initialUpdates = true; return current; }
        var live = entries.getFirst().state();
        for (Update update : updates) {
            long tick = update.historical() ? update.tick() : authoritative.newestTick();
            if (update.event() instanceof BedrockReplayEvent.Boost boost) {
                int remaining = boost.duration() < 0 ? boost.duration()
                        : (int) Math.max(0, boost.duration() - authoritative.elapsedActorTicks(tick));
                player.bedrockState.movementEffects.setGlideBoost(remaining);
            }
            var replayed = authoritative.apply(tick, update.event(), (state, world) -> worlds.replayWorld(player, state, world));
            entries = replayed.isEmpty() ? entries.stream()
                    .map(entry -> entry.withState(update.event().state(entry.state()))).toList() : replayed;
            if (correctionHistory != null) correctionHistory.apply(update.historical() ? update.tick() : correctionHistory.newestTick(),
                    update.event(), (state, world) -> worlds.replayWorld(player, state, world));
        }
        updates.clear();
        initialUpdates = false;
        entries = entries.stream().map(entry -> entry.withState(BedrockReplaySnapshot.attach(entry.state(), live))).toList();
        if (entries.equals(BedrockProfileState.profileEntries(current.carry()))) return current;
        return new PredictionCommit(new BedrockNextTickStates(entries),
                BedrockNextTickVelocityDerivation.profileStateVelocities(entries));
    }

    BedrockSimulation.Input prepareInput(
            BedrockSimulation.Input input) {
        if (!initialUpdates) return input;
        for (Update update : updates) {
            input = BedrockReplaySnapshot.withState(input, update.event().state(input.previousState()));
            input = update.event().input(input, 0);
        }
        return input;
    }

    Vec3 predictionStart(Vec3 fallback) {
        if (!initialUpdates) return fallback;
        for (Update update : updates) {
            if (update.event() instanceof BedrockReplayEvent.Transform transform) fallback = transform.correction().position();
            else if (update.event() instanceof BedrockReplayEvent.Reposition move) fallback = BedrockVectorAdapter.toJava(move.position());
        }
        return fallback;
    }

    void correction(CultPlayer player, BedrockMovementCorrection correction) {
        if (correctionHistory == null) correctionHistory = authoritative.copy();
        correctionHistory.apply(correction.tick(), new BedrockReplayEvent.Transform(correction),
                (state, world) -> worlds.replayWorld(player, state, world));
    }

    void record(CultPlayer player, PredictionResult result, PredictionCommit commit) {
        if (!(result.getAcceptedClosestToTarget() instanceof PredVector selected)) return;
        var vector = selected.firstInLineage(BedrockPredVector.class);
        if (vector == null || vector.simulationInput() == null) return;
        var entries = BedrockProfileState.profileEntries(commit.carry());
        if (entries.isEmpty()) return;
        var input = vector.simulationInput();
        var auth = result.getSimulationContext().getBedrockInput();
        Vec3d external = entries.getFirst().state().physicalFeetPosition()
                .subtract(vector.movementResult().predictedState().physicalFeetPosition());
        var lineage = BedrockProfileState.entryInLineage(vector);
        Vec3d addition = vector.packetModifiersLength() > 0 && !vector.isKnockback() && lineage != null
                ? input.previousState().velocity().subtract(lineage.state().velocity()) : Vec3d.ZERO;
        var frame = new BedrockActorHistory.Frame(auth.getClientTick(), input, entries, entries,
                vector.isKnockback() ? input.previousState().velocity() : null, addition, external,
                BedrockVectorAdapter.toBedrock(auth.getPosition()), auth.getReportedEndOfTickVelocity() == null
                    ? null : BedrockVectorAdapter.toBedrock(auth.getReportedEndOfTickVelocity()), List.of());
        authoritative.record(frame);
        if (initialUpdates) {
            updates.clear();
            initialUpdates = false;
        }
        if (correctionHistory != null) correctionHistory.advance(frame, (state, world) -> worlds.replayWorld(player, state, world));
    }

    void converged() { correctionHistory = null; }

    private record Update(long tick, boolean historical, BedrockReplayEvent event) { }
}
