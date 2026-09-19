package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.bridge.GeyserBedrockBridgeRuntime;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockVehicleCorrection;
import ac.cult.cultac.checks.impl.prediction.PredictionCommit;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.PredictionSetbackState;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import java.util.ArrayDeque;
import net.minecraft.world.phys.Vec3;

/** Owns a controlled vehicle's correction timeline on the player's packet thread. */
public final class BedrockVehicleCorrections {
    private static final int CORRECTION_INTERVAL_TICKS = 5;
    private final BedrockVehicleHistory history = new BedrockVehicleHistory();
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final BedrockBlockCollisionWorldSampler worlds = new BedrockBlockCollisionWorldSampler();
    private final BedrockMovementInputFactory inputs = new BedrockMovementInputFactory();
    private PacketEntity controlled;
    private long runtimeId = -1;
    // Only this token is read across threads, to discard superseded transport work.
    private volatile long generation;
    private Request requested;

    public long generation() { return generation; }

    public void request(CultPlayer player, int vehicleId, Vec3 position, Vec3 velocity,
                        float yaw, float pitch, boolean onGround, int transaction) {
        request(player, vehicleId, position, velocity, yaw, pitch, onGround, transaction, null);
    }

    public void request(CultPlayer player, int vehicleId, Vec3 position, Vec3 velocity,
                        float yaw, float pitch, boolean onGround, int transaction, PredictionSetbackState setback) {
        var vehicle = player.compensatedEntities.getEntity(vehicleId);
        var state = setback != null ? BedrockProfileState.previousState(setback.commit().carry())
                : vehicle == null || vehicle.bedrockPrediction == null ? null
                : BedrockProfileState.previousState(vehicle.bedrockPrediction.commit().carry());
        Float angular = state != null && state.isBoat() ? state.boat().angularVelocity() : null;
        if (vehicle != null && vehicle.isBoat()) yaw += 90.0F;
        if (setback != null && state != null && state.isVehicle()
                && (state.isBoat() ? state.boat().actor() : state.horse().actor()) == vehicle) {
            yaw = state.inputFrame().yaw();
            pitch = state.inputFrame().pitch();
        }
        requested = new Request(vehicleId, transaction, new BedrockVehicleCorrection(0, generation, vehicleId, -1, -1,
                position, velocity, yaw, pitch, onGround,
                player.getSetbackTeleportUtil().getActiveBedrockCoordinateFrame(), transaction, angular));
        sendRequested(player);
    }

    public void requestSetback(int vehicleId, int transaction) {
        // Prediction listeners run before record(); take the completed snapshot there.
        requested = new Request(vehicleId, transaction, null);
    }

    private void sendRequested(CultPlayer player) {
        if (requested == null || controlled == null || controlled != BedrockVehicleControl.controlledVehicle(player)
                || requested.vehicleId() != controlled.getEntityId() || history.newestTick() <= 0) return;
        var target = requested.target();
        var correction = target == null
                ? history.createCorrection(generation, requested.vehicleId(), runtimeId, requested.transaction())
                : new BedrockVehicleCorrection(0, generation, requested.vehicleId(), runtimeId,
                    history.newestTick(), target.position(), target.velocity(), target.yaw(), target.pitch(),
                    target.onGround(), target.coordinates(), requested.transaction(), target.angularVelocity());
        if (correction == null) return;
        if (GeyserBedrockBridgeRuntime.sendVehicleCorrection(player.user, correction)) {
            if (!pending.isEmpty()) pending.getLast().replacementPending = true;
            requested = null;
        }
    }

    public void observe(CultPlayer player, BedrockVehicleCorrection correction) {
        if (correction.controlGeneration() >= 0 && correction.controlGeneration() != generation) return;
        int transaction = correction.teleportTransaction() < 0
                ? player.getSetbackTeleportUtil().bedrockVehicleCorrectionTransaction(correction)
                : correction.teleportTransaction();
        correction = new BedrockVehicleCorrection(correction.sequence(), generation, correction.vehicleId(), correction.runtimeId(),
                correction.tick(), correction.position(), correction.velocity(), correction.yaw(), correction.pitch(),
                correction.onGround(), correction.coordinates(), transaction, correction.angularVelocity());
        var entity = player.compensatedEntities.getEntity(correction.vehicleId());
        if (BedrockVehicleControl.isSupported(entity)) {
            // Only the latest refresh of this correction needs acknowledgement and validation.
            pending.removeIf(entry -> entry.vehicle == entity && entry.correction.teleportTransaction() == transaction);
            pending.addLast(new Pending(correction, entity));
        }
    }

    public void acknowledge(long sequence) {
        for (Pending entry : pending) {
            if (entry.correction.sequence() == sequence) { entry.received = true; return; }
        }
    }

    public void beginFrame(CultPlayer player, long tick, int vehicleId, long actorRuntimeId) {
        PacketEntity vehicle = BedrockVehicleControl.controlledVehicle(player);
        if (vehicle == null || vehicle.getEntityId() != vehicleId || tick <= history.newestTick()) return;
        bind(vehicle, actorRuntimeId);
        var iterator = pending.iterator();
        while (iterator.hasNext()) {
            Pending entry = iterator.next();
            if (entry.applied) continue;
            var correction = entry.correction;
            if (entry.vehicle != vehicle || correction.runtimeId() != runtimeId) {
                iterator.remove();
                continue;
            }
            PredictionCommit commit = history.correct(correction, (state, world) -> worlds.replayWorld(player, state, world));
            if (commit == null) {
                iterator.remove();
                // Receipt of an expired packet cannot establish a position. Our pending
                // teleport needs a new correction at a tick present in the saved history.
                if (correction.teleportTransaction() >= 0) {
                    requested = new Request(correction.vehicleId(), correction.teleportTransaction(), correction);
                    sendRequested(player);
                }
                continue;
            }
            vehicle.bedrockPrediction = new BedrockVehiclePredictionState(commit, null);
            BedrockVehiclePredictionState.commitTransform(vehicle, commit.carry());
            player.checkManager.getSimulationProcessor().relocateBedrockPassenger(BedrockProfileState.previousState(commit.carry()));
            entry.applied = true;
        }
    }

    public void record(CultPlayer player, BedrockAuthInputFrame frame, PacketEntity vehicle,
                       PredictionResult result, PredictionCommit commit) {
        bind(vehicle, frame.getPredictedVehicleId());
        // advance() already saved the simulated tick while the client is catching up.
        if (!isCorrecting(vehicle)) history.record(result, commit);
        else if (requested == null && awaitingValidation(vehicle) && frame.getClientTick() == history.newestTick()
                && (result == null || result.getFlagSeverity() > 0.0D || !result.getFlags().isEmpty() || !matchesPosition(frame))) {
            Pending latest = pending.getLast();
            if (frame.getClientTick() - latest.correction.tick() > CORRECTION_INTERVAL_TICKS) {
                requestSetback(vehicle.getEntityId(), latest.correction.teleportTransaction());
            }
        }
        sendRequested(player);
    }

    public Tick advance(CultPlayer player, SimulationContext context) {
        if (!isCorrecting(context.getVehicle())) return null;
        var input = inputs.create(player, context);
        if (input == null) return null;
        var commit = history.advance(input, BedrockProfileState.profileEntries(context),
                (state, world) -> worlds.replayWorld(player, state, world));
        return commit == null ? null : new Tick(commit, BedrockVehiclePredictionState.passengerPosition(input.previousState()));
    }

    public record Tick(PredictionCommit commit, Vec3 passengerPosition) { }

    private void bind(PacketEntity vehicle, long actorRuntimeId) {
        if (controlled != null && (vehicle != controlled || actorRuntimeId != runtimeId)) clear();
        controlled = vehicle;
        runtimeId = actorRuntimeId;
    }

    public boolean isCorrecting(PacketEntity vehicle) {
        return vehicle != null && vehicle == controlled && pending.stream().anyMatch(entry -> entry.applied);
    }

    public boolean awaitingValidation(PacketEntity vehicle) {
        return isCorrecting(vehicle) && !pending.getLast().replacementPending
                && pending.stream().allMatch(entry -> entry.applied && entry.received);
    }

    public void finishPrediction(CultPlayer player, PredictionResult result) {
        if (result == null || result.isExempt() || result.getFlagSeverity() > 0.0D || !result.getFlags().isEmpty()
                || result.getSimulationContext() == null || !awaitingValidation(result.getSimulationContext().getVehicle())) return;
        var frame = result.getSimulationContext().getBedrockInput();
        if (frame == null || !matchesPosition(frame)) return;
        pending.removeIf(entry -> {
            if (!entry.applied) return false;
            return player.getSetbackTeleportUtil().completeBedrockVehicleCorrection(entry.correction);
        });
    }

    private boolean matchesPosition(BedrockAuthInputFrame frame) {
        return history.matchesPosition(frame.getClientTick(), BedrockVectorAdapter.toBedrock(frame.getPosition()),
                CultAPI.INSTANCE.getConfigManager().getBedrockMovementPositionFlagThreshold());
    }

    public void clear() {
        generation++;
        history.clear();
        pending.clear();
        requested = null;
        controlled = null;
        runtimeId = -1;
    }

    // A null target requests the latest simulated state; other targets are fixed teleports or rollbacks.
    private record Request(int vehicleId, int transaction, BedrockVehicleCorrection target) { }

    private static final class Pending {
        final BedrockVehicleCorrection correction;
        final PacketEntity vehicle;
        boolean received;
        boolean applied;
        boolean replacementPending;
        Pending(BedrockVehicleCorrection correction, PacketEntity vehicle) {
            this.correction = correction;
            this.vehicle = vehicle;
        }
    }
}
