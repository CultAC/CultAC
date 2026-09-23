package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.integration.BedrockProfileState.Entry;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionCommit;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.player.CultPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import net.minecraft.world.phys.Vec3;

/** Ordered client-visible updates publish a replayed continuation once its following frame exists. */
final class BedrockMovementRewind {
    private final BedrockBlockCollisionWorldSampler worlds = new BedrockBlockCollisionWorldSampler();
    private final BedrockActorHistory authoritative;
    private final List<Update> updates = new ArrayList<>();
    private boolean initialUpdates;

    BedrockMovementRewind() { this(new BedrockActorHistory()); }
    BedrockMovementRewind(BedrockActorHistory history) { authoritative = history; }

    BedrockReplayEvent.Metadata metadata(long tick, BedrockReplayEvent.Metadata event) {
        return authoritative.metadata(tick, event);
    }

    void clear() {
        authoritative.clear();
        updates.clear();
        initialUpdates = false;
    }

    void queue(long tick, boolean historical, BedrockReplayEvent event) {
        updates.add(new Update(tick, historical, event));
    }

    PredictionCommit apply(CultPlayer player, PredictionCommit current) {
        return apply(player, current, (state, world) -> worlds.replayWorld(player, state, world));
    }

    PredictionCommit apply(CultPlayer player, PredictionCommit current,
            BiFunction<BedrockMovementState, BedrockWorldSnapshot, BedrockWorldSnapshot> worldSampler) {
        if (updates.isEmpty()) return current;
        List<Entry> entries = BedrockProfileState.profileEntries(current.carry());
        if (entries.isEmpty()) { initialUpdates = true; return current; }
        var live = entries.getFirst().state();
        var deferred = new ArrayList<Update>();
        // Ordinary attribute delivery precedes the corrected-frame processing phase. A replay
        // published at that phase can supersede it even when the packet followed the correction.
        var ordered = java.util.stream.Stream.concat(
                updates.stream().filter(this::ordinaryMovement),
                updates.stream().filter(update -> !ordinaryMovement(update))).toList();
        for (Update update : ordered) {
            if (update.historical() && update.tick() >= authoritative.newestTick()) {
                deferred.add(update);
                continue;
            }
            boolean stale = update.historical() && update.tick() < authoritative.oldestTick();
            var event = stale ? update.event().ordinary() : update.event();
            if (ordinaryMovement(update)
                    || !update.historical() && event instanceof BedrockReplayEvent.HorseMetadata) {
                // Apply ordinary updates to live state; replaying metadata would restore stale attributes.
                entries = entries.stream().map(entry -> entry.withState(event.state(entry.state()))).toList();
                continue;
            }
            if (!update.historical() && event instanceof BedrockReplayContextEvent) {
                authoritative.ordinary(event);
                entries = entries.stream().map(entry -> entry.withState(event.state(entry.state()))).toList();
                continue;
            }
            long tick = update.historical() && !stale ? update.tick() : authoritative.newestTick();
            if (stale && event instanceof BedrockReplayEvent.Boost) tick = authoritative.oldestTick();
            // A matching confirmation must neither replay actions nor overwrite a later live replacement.
            if (authoritative.matches(tick, event)) continue;
            if (update.event() instanceof BedrockReplayEvent.Boost boost && boost.duration() != 0) {
                int remaining = boost.duration() == -1 ? -1
                        : (int) Math.max(0, boost.duration() - Math.max(0, authoritative.newestTick() - update.tick()));
                player.bedrockState.movementEffects.setGlideBoost(remaining);
            }
            var replayed = authoritative.apply(tick, event, worldSampler);
            entries = replayed.isEmpty() ? entries.stream()
                    .map(entry -> entry.withState(event.state(entry.state()))).toList() : replayed;
        }
        updates.clear();
        updates.addAll(deferred);
        initialUpdates = false;
        entries = entries.stream().map(entry -> entry.withState(BedrockReplaySnapshot.attach(entry.state(), live))).toList();
        if (entries.equals(BedrockProfileState.profileEntries(current.carry()))) return current;
        return new PredictionCommit(new BedrockNextTickStates(entries),
                BedrockNextTickVelocityDerivation.profileStateVelocities(entries));
    }

    private boolean ordinaryMovement(Update update) {
        boolean attributes = update.event() instanceof BedrockReplayAttributeEvent
                || update.event() instanceof BedrockReplayContextEvent context && context.movementAttribute() != null;
        return attributes && (!update.historical() || update.tick() < authoritative.oldestTick());
    }

    BedrockSimulation.Input prepareInput(
            BedrockSimulation.Input input) {
        if (!initialUpdates) return input;
        for (Update update : updates) {
            if (update.historical()) continue;
            input = BedrockReplaySnapshot.withState(input, update.event().state(input.previousState()));
            input = update.event().input(input, 0);
        }
        return input;
    }

    Vec3 predictionStart(Vec3 fallback) {
        if (!initialUpdates) return fallback;
        for (Update update : updates) {
            if (update.historical()) continue;
            if (update.event() instanceof BedrockReplayEvent.Transform transform) fallback = transform.correction().position();
            else if (update.event() instanceof BedrockReplayEvent.Reposition move) fallback = BedrockVectorAdapter.toJava(move.position());
        }
        return fallback;
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
            updates.removeIf(update -> !update.historical());
            initialUpdates = false;
        }
    }
    private record Update(long tick, boolean historical, BedrockReplayEvent event) { }
}
