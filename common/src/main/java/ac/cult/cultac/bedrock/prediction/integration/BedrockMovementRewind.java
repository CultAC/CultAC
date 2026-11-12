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
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

/** Ordered client-visible updates publish a replayed continuation once its following frame exists. */
final class BedrockMovementRewind {
    private final BedrockBlockCollisionWorldSampler worlds = new BedrockBlockCollisionWorldSampler();
    private final BedrockActorHistory authoritative;
    private final List<Update> updates = new ArrayList<>();
    private boolean initialUpdates;

    BedrockMovementRewind() {
        this(new BedrockActorHistory());
    }

    BedrockMovementRewind(BedrockActorHistory history) {
        authoritative = history;
    }

    BedrockReplayEvent.Metadata metadata(long tick, BedrockReplayEvent.Metadata event) {
        return authoritative.metadata(tick, event);
    }

    BedrockReplayEvent.Metadata metadata(long tick, BedrockReplayEvent.Metadata event, int flagWords) {
        return authoritative.metadata(tick, event, flagWords);
    }

    void clear() {
        authoritative.clear();
        updates.clear();
        initialUpdates = false;
    }

    /** Whether an acknowledged update still waits for the frame after its tick, which the client takes it on. */
    boolean defersUpdates() {
        return !updates.isEmpty();
    }

    void queue(long tick, boolean historical, BedrockReplayEvent event) {
        updates.add(new Update(tick, historical, event));
    }

    PredictionCommit apply(CultPlayer player, PredictionCommit current) {
        if (updates.isEmpty()) return current;
        if (player == null) return apply(null, current, (state, world) -> world);
        var actors = player.bedrockState.movementCorrections.replaySolidActors(player);
        return apply(player, current, (state, world) -> worlds.replayWorld(player, state, world),
                world -> BedrockReplayCollisionWorld.withNearbyActors(world, actors));
    }

    PredictionCommit apply(
            CultPlayer player,
            PredictionCommit current,
            BiFunction<BedrockMovementState, BedrockWorldSnapshot, BedrockWorldSnapshot> worldSampler) {
        return apply(player, current, worldSampler, UnaryOperator.identity());
    }

    private PredictionCommit apply(
            CultPlayer player,
            PredictionCommit current,
            BiFunction<BedrockMovementState, BedrockWorldSnapshot, BedrockWorldSnapshot> worldSampler,
            UnaryOperator<BedrockWorldSnapshot> actorSampler) {
        if (updates.isEmpty()) return current;
        List<Entry> entries = BedrockProfileState.profileEntries(current.carry());
        if (entries.isEmpty()) {
            initialUpdates = true;
            return current;
        }
        if (player != null && player.bedrockState != null) {
            entries = entries.stream()
                    .map(entry -> entry.state().isVehicle()
                            ? entry
                            : entry.withState(player.bedrockState.applyConfirmedBoundingBoxSize(
                                    entry.state(), entry.state().inputFrame(), player.dimensionScale)))
                    .toList();
        }
        var live = entries.getFirst().state();
        var deferred = new ArrayList<Update>();
        // The client replays a correction of its vehicle with the attributes its replayed ticks had: the updates it
        // received since its last tick are lost.
        boolean takesVehicleCorrection = updates.stream()
                .anyMatch(update -> update.event() instanceof BedrockReplayEvent.Transform transform
                        && transform.correction().vehicle()
                        && !deferred(update));
        // A later boost update already took effect live when it was acknowledged, so an earlier one never re-arms.
        Update latestBoost = null;
        for (Update update : updates) {
            if (update.event() instanceof BedrockReplayEvent.Boost) latestBoost = update;
        }
        // Arrival order: an attribute update and a replay acknowledged together must both survive.
        for (Update update : updates) {
            boolean attributes = attributes(update);
            if (attributes && takesVehicleCorrection) continue;
            if (!attributes && deferred(update)) {
                deferred.add(update);
                continue;
            }
            boolean stale = update.historical() && update.tick() < authoritative.oldestTick();
            var event = stale ? update.event().ordinary() : update.event();
            if (attributes) {
                // The client uses an attribute value from its first tick after the packet and never re-runs
                // the ticks since the stamp with it. A stamped value equal to that tick's own changes nothing.
                if (update.historical() && !stale && authoritative.matches(update.tick(), event)) continue;
                // Recorded on the newest frame, so a replay from an earlier frame applies it again there.
                authoritative.ordinary(event);
                entries = entries.stream()
                        .map(entry -> entry.withState(event.state(entry.state())))
                        .toList();
                continue;
            }
            if (!update.historical() && event instanceof BedrockReplayEvent.HorseMetadata) {
                // Apply ordinary updates to live state; replaying metadata would restore stale attributes.
                entries = entries.stream()
                        .map(entry -> entry.withState(event.state(entry.state())))
                        .toList();
                continue;
            }
            if (!update.historical() && event instanceof BedrockReplayContextEvent) {
                authoritative.ordinary(event);
                entries = entries.stream()
                        .map(entry -> entry.withState(event.state(entry.state())))
                        .toList();
                continue;
            }
            long tick = update.historical() && !stale ? update.tick() : authoritative.newestTick();
            if (stale && event instanceof BedrockReplayEvent.Boost) tick = authoritative.oldestTick();
            // A matching confirmation must neither replay actions nor overwrite a later live replacement.
            if (authoritative.matches(tick, event)) continue;
            if (update == latestBoost
                    && update.event() instanceof BedrockReplayEvent.Boost boost
                    && boost.duration() != 0) {
                int remaining = boost.duration() == -1
                        ? -1
                        : (int) Math.max(0, boost.duration() - Math.max(0, authoritative.newestTick() - update.tick()));
                player.bedrockState.movementEffects.setGlideBoost(remaining);
            }
            var replayed = authoritative.apply(tick, event, worldSampler, actorSampler);
            entries = replayed.isEmpty()
                    ? entries.stream()
                            .map(entry -> entry.withState(event.state(entry.state())))
                            .toList()
                    : replayed;
        }
        updates.clear();
        updates.addAll(deferred);
        initialUpdates = false;
        entries = entries.stream()
                .map(entry -> entry.withState(BedrockReplaySnapshot.attach(entry.state(), live)))
                .toList();
        if (entries.equals(BedrockProfileState.profileEntries(current.carry()))) return current;
        return new PredictionCommit(
                new BedrockNextTickStates(entries), BedrockNextTickVelocityDerivation.profileStateVelocities(entries));
    }

    /** A replay of a tick waits for the frame after it, the tick the client takes it on. */
    private boolean deferred(Update update) {
        return update.historical() && update.tick() >= authoritative.newestTick();
    }

    private static boolean attributes(Update update) {
        return update.event() instanceof BedrockReplayAttributeEvent
                || update.event() instanceof BedrockReplayContextEvent context && context.movementAttribute() != null;
    }

    /** Removes the queued attribute updates and returns those that change the actor, in arrival order. */
    List<BedrockReplayAttributeEvent> drainAttributes() {
        var changes = new ArrayList<BedrockReplayAttributeEvent>();
        updates.removeIf(update -> {
            if (!(update.event() instanceof BedrockReplayAttributeEvent event)) return false;
            boolean stale = update.historical() && update.tick() < authoritative.oldestTick();
            if (!update.historical() || stale || !authoritative.matches(update.tick(), event)) changes.add(event);
            return true;
        });
        return changes;
    }

    BedrockSimulation.Input prepareInput(BedrockSimulation.Input input) {
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
            if (update.event() instanceof BedrockReplayEvent.Transform transform)
                fallback = transform.correction().position();
            else if (update.event() instanceof BedrockReplayEvent.Reposition move)
                fallback = BedrockVectorAdapter.toJava(move.position());
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
        Vec3d external = BedrockVectorAdapter.toBedrock(result.getPositionOnlyDelta());
        var lineage = BedrockProfileState.entryInLineage(vector);
        Vec3d addition = vector.packetModifiersLength() > 0 && !vector.isKnockback() && lineage != null
                ? input.previousState().velocity().subtract(lineage.state().velocity())
                : Vec3d.ZERO;
        var frame = new BedrockActorHistory.Frame(
                auth.getClientTick(),
                input,
                entries,
                entries,
                vector.isKnockback() ? input.previousState().velocity() : null,
                addition,
                external,
                BedrockVectorAdapter.toBedrock(auth.getPosition()),
                auth.getReportedEndOfTickVelocity() == null
                        ? null
                        : BedrockVectorAdapter.toBedrock(auth.getReportedEndOfTickVelocity()),
                List.of(),
                vector.movementResult().collisionFetchBox());
        authoritative.record(frame);
        if (initialUpdates) {
            updates.removeIf(update -> !update.historical());
            initialUpdates = false;
        }
    }

    private record Update(long tick, boolean historical, BedrockReplayEvent event) {}
}
