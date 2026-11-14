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
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * The updates the client acknowledged, taken in arrival order. A timed update is recorded on the frame of its tick,
 * which it marks; an untimed one changes the live state only. Some timed updates also make the client run its frames
 * again: a correction that differs from what its frame reported, a velocity, a boost or an effect that starts or stops.
 * That replay starts at the oldest frame any update has marked since the last one, once the frame after the update's
 * tick exists.
 */
final class BedrockMovementRewind {
    private final BedrockBlockCollisionWorldSampler worlds = new BedrockBlockCollisionWorldSampler();
    private final BedrockActorHistory authoritative;
    private final List<Update> updates = new ArrayList<>();
    private boolean initialUpdates;
    private final BedrockEffectClock glideBoost = new BedrockEffectClock();
    // A correction within this distance, squared, of what its frame reported is the state the client already has.
    private static final double TAKEN_DISTANCE_SQUARED = 1.0E-5D;

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

    /** The actor's timed effects were reset: none runs for the client any more. */
    void clearEffects() {
        glideBoost.clear();
    }

    /** Whether an acknowledged update still waits for the frame after its tick, which the client takes it on. */
    boolean defersUpdates() {
        return !updates.isEmpty();
    }

    void queue(long tick, boolean historical, BedrockReplayEvent event) {
        updates.add(new Update(tick, historical, event, historical ? taking(tick, event) : null));
    }

    /** How the client takes {@code event}, stamped {@code tick}, as it arrives. */
    private BedrockReplayTaking taking(long tick, BedrockReplayEvent event) {
        long now = authoritative.newestTick();
        return switch (event) {
            case BedrockReplayEvent.Motion motion -> BedrockReplayTaking.REPLAYS;
            case BedrockReplayEvent.Transform transform ->
                taken(tick, transform) ? BedrockReplayTaking.IGNORED : BedrockReplayTaking.REPLAYS;
            // A boost replays only when it starts one that was not running or stops one that was.
            case BedrockReplayEvent.Boost boost ->
                glideBoost.switches(tick, now, boost.duration())
                        ? BedrockReplayTaking.REPLAYS
                        : BedrockReplayTaking.MARKS;
            case BedrockReplayEvent.GeyserBoost boost -> {
                var current = authoritative.current();
                boolean runs = !current.isEmpty() && current.getFirst().state().geyserBoostTicks() != 0;
                boolean left = boost.duration() == -1 || boost.duration() > Math.max(0, now - tick);
                yield current.isEmpty() || left != runs ? BedrockReplayTaking.REPLAYS : BedrockReplayTaking.MARKS;
            }
            // An effect replays only when it changes the level the actor has.
            case BedrockReplayContextEvent context
            when context.effectId() != null -> {
                var newest = authoritative.frame(now);
                yield newest == null
                                || context.changesEffect(newest.input()
                                        .snapshot()
                                        .movementContext()
                                        .effectState())
                        ? BedrockReplayTaking.REPLAYS
                        : BedrockReplayTaking.MARKS;
            }
            default -> BedrockReplayTaking.MARKS;
        };
    }

    /** Whether the frame of {@code tick} reported the position, velocity and ground state a correction carries. */
    private boolean taken(long tick, BedrockReplayEvent.Transform transform) {
        var correction = transform.correction();
        var frame = authoritative.frame(tick);
        if (correction.vehicle() || frame == null || frame.observedVelocity() == null) return false;
        return squaredDistance(BedrockVectorAdapter.toBedrock(correction.position()), frame.observedPosition())
                        <= TAKEN_DISTANCE_SQUARED
                && squaredDistance(BedrockVectorAdapter.toBedrock(correction.velocity()), frame.observedVelocity())
                        <= TAKEN_DISTANCE_SQUARED
                && frame.end().stream()
                        .allMatch(entry -> entry.state().collisionFlags().onGround() == correction.onGround());
    }

    private static double squaredDistance(Vec3d first, Vec3d second) {
        Vec3d offset = first.subtract(second);
        return offset.x() * offset.x() + offset.y() * offset.y() + offset.z() * offset.z();
    }

    PredictionCommit apply(CultPlayer player, PredictionCommit current) {
        if (updates.isEmpty()) return current;
        if (player == null) return apply(null, current, (state, world) -> world);
        var actors = player.bedrockState.movementCorrections.replaySolidActors(player);
        return apply(
                player,
                current,
                (state, world) -> worlds.replayWorld(player, state, world),
                // A replayed tick meets the solid actors its live tick met, at their replay boxes.
                (frame, state, world) -> BedrockReplayCollisionWorld.withNearbyActors(
                        world,
                        frame.solidActors() == null
                                ? actors.around(reachStart(state))
                                : actors.met(frame.solidActors())));
    }

    PredictionCommit apply(
            CultPlayer player,
            PredictionCommit current,
            BiFunction<BedrockMovementState, BedrockWorldSnapshot, BedrockWorldSnapshot> worldSampler) {
        return apply(player, current, worldSampler, (frame, state, world) -> world);
    }

    private PredictionCommit apply(
            CultPlayer player,
            PredictionCommit current,
            BiFunction<BedrockMovementState, BedrockWorldSnapshot, BedrockWorldSnapshot> worldSampler,
            BedrockReplayTick.Actors actorSampler) {
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
        boolean replays = false;
        // The updates whose change to the live state a replay does not carry: they are taken again after it.
        var unrecorded = new ArrayList<BedrockReplayEvent>();
        for (Update update : updates) {
            boolean attributes = attributes(update);
            if (attributes && takesVehicleCorrection) continue;
            if (!attributes && deferred(update)) {
                deferred.add(update);
                continue;
            }
            boolean stale = update.historical() && update.tick() < authoritative.oldestTick();
            if (attributes) {
                var event = stale ? update.event().ordinary() : update.event();
                // The client uses an attribute value from its first tick after the packet and never re-runs
                // the ticks since the stamp with it. A stamped value equal to that tick's own changes nothing.
                if (update.historical() && !stale && authoritative.matches(update.tick(), event)) continue;
                // A stamped value is recorded on its own frame, so a later replay runs the frames since with it.
                if (!update.historical() || stale || !authoritative.attach(update.tick(), event))
                    authoritative.ordinary(event);
                entries = taken(entries, event);
                continue;
            }
            if (!update.historical()) {
                // An untimed update changes the live state only; a horse's is not recorded, because replaying
                // it would restore stale attributes.
                var event = update.event();
                if (event instanceof BedrockReplayEvent.HorseMetadata) unrecorded.add(event);
                else if (!(event instanceof BedrockReplayContextEvent)
                        && authoritative.matches(authoritative.newestTick(), event)) continue;
                else authoritative.ordinary(event);
                entries = taken(entries, event);
                continue;
            }
            if (update.taking() == BedrockReplayTaking.IGNORED) continue;
            var event = update.event();
            // An update older than the history is taken on its oldest frame.
            long tick = stale ? authoritative.oldestTick() : Math.min(update.tick(), authoritative.newestTick());
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
            if (authoritative.attach(tick, event) && update.taking() == BedrockReplayTaking.REPLAYS) {
                replays = true;
                // The effect a replay was made for still changes the speed of the state it ends with.
                if (event instanceof BedrockReplayContextEvent) unrecorded.add(event);
            } else entries = taken(entries, event);
        }
        // One replay, from the oldest frame any update marked, takes them all.
        if (replays) {
            var replayed = authoritative.replay(worldSampler, actorSampler);
            if (!replayed.isEmpty()) {
                entries = replayed;
                for (var event : unrecorded) entries = taken(entries, event);
            }
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

    /** The box a live tick starting at {@code state} gathers its solid actors around. */
    private static SimpleCollisionBox reachStart(BedrockMovementState state) {
        var box = state.collisionBox();
        Vec3d feet = state.physicalFeetPosition();
        // A player on foot gathers them around its standing box in any pose.
        double top = state.isVehicle() ? box.maxY() : Math.max(box.maxY(), feet.y() + STANDING_HEIGHT);
        return new SimpleCollisionBox(box.minX(), box.minY(), box.minZ(), box.maxX(), top, box.maxZ());
    }

    private static final double STANDING_HEIGHT = 1.8D;

    /** A replay of a tick waits for the frame after it, the tick the client takes it on. */
    private boolean deferred(Update update) {
        return update.taking() == BedrockReplayTaking.REPLAYS && update.tick() >= authoritative.newestTick();
    }

    /** The live states once the actor has taken {@code event}. */
    private static List<Entry> taken(List<Entry> entries, BedrockReplayEvent event) {
        return entries.stream()
                .map(entry -> {
                    var state = event.state(entry.state());
                    // An effect changes the speed of the live state only: a replay does not take it again.
                    return entry.withState(
                            event instanceof BedrockReplayContextEvent effect ? effect.withEffectSpeed(state) : state);
                })
                .toList();
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
                null,
                vector.movementResult().collisionFetchBox(),
                player == null || player.bedrockState == null
                        ? null
                        : player.bedrockState.movementCorrections.solidActorsMet(auth.getClientTick()));
        authoritative.record(frame);
        if (initialUpdates) {
            updates.removeIf(update -> !update.historical());
            initialUpdates = false;
        }
    }

    /** A queued update; {@code taking} is how the client took a timed one, null for an untimed one. */
    private record Update(long tick, boolean historical, BedrockReplayEvent event, BedrockReplayTaking taking) {}
}
