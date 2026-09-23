package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.integration.BedrockProfileState.Entry;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockForwardTick;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiFunction;

/** Replays only movement, with recorded external inputs and no packet/check side effects. */
final class BedrockReplayTick {
    private BedrockReplayTick() { }

    static List<Entry> advance(BedrockActorHistory.Frame frame, List<Entry> previous,
            List<BedrockActorHistory.Event> active,
            BiFunction<BedrockMovementState, BedrockWorldSnapshot, BedrockWorldSnapshot> world) {
        var outcomes = new ArrayList<List<Entry>>();
        for (Entry entry : previous) {
            var recorded = frame.input();
            var state = entry.state().withCoordinateFrame(recorded.previousState().coordinateFrame());
            if (frame.receivedFlags() != null) state = frame.receivedFlags().state(state);
            if (state.isHorse()) {
                var horse = recorded.previousState().horse();
                state = state.withHorse(state.horse().forFrame(horse.metadataRevision(), horse.standing(), horse.release()));
            }
            state = state.withVelocityAndCollisionFlags(frame.imposedVelocity() != null
                    ? frame.imposedVelocity() : state.velocity().add(frame.addedVelocity()), state.collisionFlags());
            var request = new BedrockSimulation.Input(state, recorded.frame(), recorded.intent(), recorded.snapshot(),
                    recorded.canStep(), recorded.maxUpStep(), entry.mobJumpComponent(), recorded.actorMovementTick(),
                    recorded.acceptedTeleport(), recorded.control(), recorded.glideBoost());
            for (var event : active) request = frame.tick() <= event.throughTick()
                    ? event.value().input(request, state.simulationTick() - event.simulationTick())
                    : event.value().restoreInput(request, recorded);
            Vec3d shift = state.physicalFeetPosition().subtract(recorded.previousState().physicalFeetPosition());
            float dx = (float) shift.x(), dy = (float) shift.y(), dz = (float) shift.z();
            if (!(dx * dx + dy * dy + dz * dz < 4.0F)) {
                request = new BedrockSimulation.Input(state, request.frame(), request.intent(),
                        world.apply(state, request.snapshot()), request.canStep(), request.maxUpStep(),
                        request.mobJumpComponent(), request.actorMovementTick(), request.acceptedTeleport(),
                        request.control(), request.glideBoost());
            }
            for (var candidate : BedrockForwardTick.simulate(request)) {
                var movement = candidate.movementResult();
                var end = movement.predictedState();
                end = end.withExternalDisplacement(frame.externalDisplacement());
                outcomes.add(BedrockForwardTick.finish(movement, end, frame.observedVelocity()).stream()
                        .map(next -> new Entry(next, candidate.mobJumpComponent())).toList());
            }
        }
        // Observations rank legal candidates only. They never become state or widen its bounds.
        return outcomes.stream().min(Comparator.comparingDouble(entries -> entries.stream()
                .mapToDouble(entry -> score(frame, entry.state())).min().orElseThrow())).orElseThrow();
    }

    private static double score(BedrockActorHistory.Frame frame, BedrockMovementState state) {
        double position = state.physicalFeetPosition().subtract(frame.observedPosition()).length();
        double velocity = frame.observedVelocity() == null ? 0 : state.velocity().subtract(frame.observedVelocity()).length();
        return position + velocity;
    }
}
