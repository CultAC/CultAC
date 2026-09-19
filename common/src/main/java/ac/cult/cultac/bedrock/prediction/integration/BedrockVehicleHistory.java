package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockHorseMovement;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockVehicleCorrection;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionCommit;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.BiFunction;

/** Bounded history for one continuously controlled vehicle. Owned by the player's packet thread. */
public final class BedrockVehicleHistory {
    public static final int CAPACITY = 40;
    private final ArrayList<Frame> frames = new ArrayList<>(CAPACITY);

    public void clear() { frames.clear(); }
    public long newestTick() { return frames.isEmpty() ? -1 : frames.getLast().tick(); }

    BedrockVehicleCorrection createCorrection(long generation, int vehicleId, long runtimeId, int transaction) {
        if (frames.isEmpty() || frames.getLast().end().isEmpty()) return null;
        var frame = frames.getLast();
        var state = frame.end().getFirst().state();
        // The history key is the packet tick; the state's input tick is a local sequence.
        return new BedrockVehicleCorrection(0, generation, vehicleId, runtimeId, frame.tick(),
                BedrockVectorAdapter.toJava(state.physicalFeetPosition()), BedrockVectorAdapter.toJava(state.velocity()),
                state.inputFrame().yaw(), state.inputFrame().pitch(), state.collisionFlags().onGround(),
                state.coordinateFrame(), transaction, state.isBoat() ? state.boat().angularVelocity() : null);
    }

    public boolean matchesPosition(long tick, Vec3d position, double threshold) {
        return !frames.isEmpty() && tick == newestTick() && frames.getLast().end().stream().anyMatch(entry ->
                entry.state().physicalFeetPosition().subtract(position).length() <= threshold);
    }

    PredictionCommit advance(BedrockMovementInputFactory.Input input, List<BedrockProfileState.Entry> states,
            BiFunction<BedrockMovementState, BedrockWorldSnapshot, BedrockWorldSnapshot> distantWorld) {
        if (input.authFrame().getClientTick() <= newestTick()) return null;
        Vec3d control = control(input.authFrame(), input.previousState());
        if (control == null || states.isEmpty()) return null;
        var end = simulate(states, input, control, distantWorld);
        record(new Frame(input.authFrame().getClientTick(), input, control, end, null));
        return commit(end);
    }

    public void record(PredictionResult result, PredictionCommit commit) {
        if (result == null || !(result.getAcceptedClosestToTarget() instanceof PredVector selected)) return;
        var candidate = (BedrockPredVector) selected.firstInLineage(BedrockPredVector.class);
        if (candidate == null || !candidate.input().previousState().isVehicle()) return;
        var input = candidate.input();
        var control = control(input.authFrame(), input.previousState());
        if (control == null) {
            clear();
            return;
        }
        List<BedrockProfileState.Entry> end = commit == null ? List.of() : BedrockProfileState.profileEntries(commit.carry());
        // Rejected positions are never a replay anchor. The correction supplies the authoritative transform.
        if (end.isEmpty() || result.getFlagSeverity() > 0.0D || !result.getFlags().isEmpty() || result.isExempt()) {
            end = List.of(new BedrockProfileState.Entry(candidate.movementResult().predictedState(), candidate.mobJumpComponent()));
        }
        var frame = new Frame(input.authFrame().getClientTick(), input, control, end, null);
        if (!frames.isEmpty() && frame.tick() == newestTick()) frames.set(frames.size() - 1, frame);
        else record(frame);
    }

    static Vec3d control(BedrockAuthInputFrame frame, BedrockMovementState state) {
        if (state.isBoat() && !state.boat().analogPaddles()) {
            return new Vec3d(frame.hasRawInputFlag(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.PADDLE_LEFT) ? 1 : 0,
                    0, frame.hasRawInputFlag(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.PADDLE_RIGHT) ? 1 : 0);
        }
        var vector = frame.getMoveVector();
        if (vector == null || !Float.isFinite(vector.x()) || !Float.isFinite(vector.z())
                || Math.abs(vector.x()) > 1.0F || Math.abs(vector.z()) > 1.0F) {
            return null;
        }
        float divisor = Math.max(1.0F, (float) Math.sqrt(vector.x() * vector.x() + vector.z() * vector.z()));
        // Boats apply the forward button after normalizing the movement vector.
        float forward = state.isBoat() && frame.hasRawInputFlag(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.UP)
                ? 1.0F : vector.z() / divisor;
        return new Vec3d(vector.x() / divisor, 0, forward);
    }

    void record(Frame frame) {
        if (!frames.isEmpty() && frame.tick() <= newestTick()) return;
        // A discontinuous tick sequence discards older snapshots; it never invents movement ticks.
        if (!frames.isEmpty() && frame.tick() != newestTick() + 1) clear();
        if (frames.size() == CAPACITY) frames.removeFirst();
        frames.add(frame);
    }

    public PredictionCommit correct(BedrockVehicleCorrection correction,
            BiFunction<BedrockMovementState, BedrockWorldSnapshot, BedrockWorldSnapshot> distantWorld) {
        int start = -1;
        for (int i = 0; i < frames.size(); i++) {
            if (frames.get(i).tick() == correction.tick()) { start = i; break; }
        }
        if (start < 0 || correction.tick() == 0) return null;
        var anchor = frames.get(start);
        List<BedrockProfileState.Entry> states = anchor.end().stream().map(entry -> entry.withState(
                corrected(entry.state(), correction))).distinct().toList();
        var replacements = new ArrayList<Frame>();
        replacements.add(new Frame(anchor.tick(), anchor.input(), anchor.control(), states, correction));
        for (int i = start + 1; i < frames.size(); i++) {
            Frame frame = frames.get(i);
            states = simulate(states, frame.input(), frame.control(), distantWorld);
            if (frame.correction() != null) states = states.stream().map(entry -> entry.withState(
                    corrected(entry.state(), frame.correction()))).distinct().toList();
            replacements.add(frame.withEnd(states));
        }
        for (int i = 0; i < replacements.size(); i++) frames.set(start + i, replacements.get(i));
        return commit(states);
    }

    private static List<BedrockProfileState.Entry> simulate(List<BedrockProfileState.Entry> states,
            BedrockMovementInputFactory.Input original, Vec3d control,
            BiFunction<BedrockMovementState, BedrockWorldSnapshot, BedrockWorldSnapshot> distantWorld) {
        var next = new LinkedHashSet<BedrockProfileState.Entry>();
        for (var entry : states) {
            var state = entry.state().withCoordinateFrame(original.previousState().coordinateFrame());
            if (state.isHorse()) {
                var horse = original.previousState().horse();
                state = state.withHorse(state.horse().forFrame(horse.metadataRevision(), horse.standing(), horse.release()));
            } else {
                var boat = original.previousState().boat();
                state = state.withBoat(state.boat().withProperties(boat.properties(), boat.analogPaddles()));
            }
            var auth = original.authFrame();
            var tick = new BedrockInputFrame(original.inputFrame().clientTick(),
                    state.isBoat() ? state.inputFrame().yaw() : BedrockHorseMovement.yawAfterControl(state.inputFrame().yaw(), auth.getYaw()),
                    state.isBoat() ? 0 : auth.getPitch() * 0.5F, false, false, false);
            BedrockWorldSnapshot world = original.worldSnapshot();
            Vec3d shift = state.physicalFeetPosition().subtract(original.previousState().physicalFeetPosition());
            // Nearby replays retain the collision shapes captured for that frame.
            float dx = (float) shift.x(), dy = (float) shift.y(), dz = (float) shift.z();
            if (!(dz * dz + dx * dx + dy * dy < 4.0F)) world = distantWorld.apply(state, world);
            var replay = BedrockSimulation.vehicleTick(new BedrockSimulation.Input(state, tick, tick.intent(), world,
                    true, state.isBoat() ? original.maxUpStep() : BedrockHorseMovement.maxUpStep(state, world.movementContext()), entry.mobJumpComponent(),
                    original.actorMovementTick(), false), control);
            next.add(new BedrockProfileState.Entry(replay.movementResult().predictedState(), replay.mobJumpComponent()));
        }
        return List.copyOf(next);
    }

    private static PredictionCommit commit(List<BedrockProfileState.Entry> states) {
        return new PredictionCommit(new BedrockNextTickStates(states), BedrockNextTickVelocityDerivation.profileStateVelocities(states));
    }

    static BedrockMovementState corrected(BedrockMovementState state, BedrockVehicleCorrection correction) {
        var flags = state.collisionFlags();
        var correctedFlags = new BedrockCollisionFlags(correction.onGround(), flags.horizontalCollision(),
                flags.verticalCollision(), flags.horizontalBlockContact(), flags.liquidClimbOut(),
                flags.verticalCollisionBelow(), flags.xCollision(), flags.zCollision());
        if (state.isBoat() && correction.angularVelocity() != null) {
            state = state.withBoat(state.boat().withAngularVelocity(correction.angularVelocity()));
        }
        return state.withCoordinateFrame(correction.coordinates())
                .withPhysicalFeetPosition(BedrockVectorAdapter.toBedrock(correction.position()), 0)
                .withRotation(correction.yaw(), correction.pitch())
                .withVelocityAndCollisionFlags(BedrockVectorAdapter.toBedrock(correction.velocity()), correctedFlags)
                .withMovementBranch(state.movementBranch() == Medium.GROUND && !correction.onGround()
                        ? Medium.AIR : state.movementBranch());
    }

    record Frame(long tick, BedrockMovementInputFactory.Input input, Vec3d control, List<BedrockProfileState.Entry> end,
                 BedrockVehicleCorrection correction) {
        Frame { end = List.copyOf(end); }
        Frame withEnd(List<BedrockProfileState.Entry> states) { return new Frame(tick, input, control, states, correction); }
    }
}
