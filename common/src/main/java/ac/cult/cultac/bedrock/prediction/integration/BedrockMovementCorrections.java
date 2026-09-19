package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.bridge.GeyserBedrockBridgeRuntime;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockMovementCorrection;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.checks.impl.prediction.PredictionCommit;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.PredictionSetbackState;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import net.minecraft.world.phys.Vec3;

/** One outstanding correction on the owning movement loop. */
public final class BedrockMovementCorrections {
    private static final int CORRECTION_INTERVAL_TICKS = 5;
    public static final int CLIENT_HISTORY_SIZE = BedrockActorHistory.CAPACITY;
    private final BedrockMovementRewind rewind = new BedrockMovementRewind();
    private long lastTick = -1;
    private long processedTicks;
    private PacketEntity controlled;
    private long runtimeId = Long.MIN_VALUE;
    private volatile long generation;
    private Pending pending;
    private int requestedTransaction = -1;

    public long generation() { return generation; }

    public void request(CultPlayer player, int vehicleId, Vec3 position, Vec3 velocity,
                        float yaw, float pitch, boolean onGround, int transaction) {
        request(player, vehicleId, position, velocity, yaw, pitch, onGround, transaction, null);
    }

    public void request(CultPlayer player, int vehicleId, Vec3 position, Vec3 velocity,
                        float yaw, float pitch, boolean onGround, int transaction, PredictionSetbackState setback) {
        var vehicle = BedrockVehicleControl.controlledVehicle(player);
        if (vehicle == null || vehicle.getEntityId() != vehicleId) return;
        clear();
        player.checkManager.getSimulationProcessor().resetBedrockVehicle(vehicle, position, velocity, yaw, pitch, onGround, setback);
        requestedTransaction = transaction;
        pending = null;
    }

    public void requestSetback(int vehicleId, int transaction) {
        if (controlled != null && controlled.getEntityId() == vehicleId) requestedTransaction = transaction;
    }

    public void observe(CultPlayer player, BedrockMovementCorrection correction) {
        if (correction.controlGeneration() != generation || runtimeId == Long.MIN_VALUE
                || correction.vehicle() != (controlled != null)
                || controlled != null && (controlled.getEntityId() != correction.vehicleId() || runtimeId != correction.runtimeId())) return;
        pending = new Pending(correction, processedTicks);
        rewind.correction(player, correction);
    }

    public void acknowledge(long sequence) {
        if (pending != null && pending.correction.sequence() == sequence) pending.received = true;
    }

    public void beginFrame(CultPlayer player, long tick, int vehicleId, long actorRuntimeId) {
        var vehicle = BedrockVehicleControl.controlledVehicle(player);
        if (vehicle != null && vehicle.getEntityId() == vehicleId) bind(vehicle, actorRuntimeId);
    }

    public void queueUpdate(long expectedGeneration, int vehicleId, long actorRuntimeId,
                            long tick, boolean historical, BedrockReplayEvent event) {
        if (expectedGeneration != generation) return;
        if (vehicleId < 0 ? controlled != null
                : controlled == null || controlled.getEntityId() != vehicleId || runtimeId != actorRuntimeId) return;
        rewind.queue(tick, historical, event);
    }

    public PredictionCommit prepareFrame(CultPlayer player, BedrockAuthInputFrame frame, PredictionCommit current) {
        var vehicle = BedrockVehicleControl.controlledVehicle(player);
        bind(vehicle, vehicle == null ? -1 : frame.getPredictedVehicleId());
        return rewind.apply(player, current);
    }

    public BedrockSimulation.Input prepareInput(
            BedrockSimulation.Input input) {
        return rewind.prepareInput(input);
    }

    public Vec3 predictionStart(Vec3 fallback) { return rewind.predictionStart(fallback); }

    private void bind(PacketEntity vehicle, long actorRuntimeId) {
        if (runtimeId != Long.MIN_VALUE && (vehicle != controlled || runtimeId != actorRuntimeId)) clear();
        controlled = vehicle;
        runtimeId = actorRuntimeId;
    }

    public void record(CultPlayer player, BedrockAuthInputFrame frame, PacketEntity vehicle,
                       PredictionResult result, PredictionCommit commit) {
        bind(vehicle, vehicle == null ? -1 : frame.getPredictedVehicleId());
        if (commit == null || frame.getClientTick() <= lastTick) return;
        if (result != null) rewind.record(player, result, commit);
        lastTick = frame.getClientTick();
        processedTicks++;
        var state = BedrockProfileState.previousState(commit.carry());
        if (state == null) return;
        double positionLimit = CultAPI.INSTANCE.getConfigManager().getBedrockMovementPositionFlagThreshold();
        double velocityLimit = CultAPI.INSTANCE.getConfigManager().getBedrockMovementVelocityFlagThreshold();
        boolean converged = result != null && result.getFlags().isEmpty()
                && frame.getReportedEndOfTickVelocity() != null
                && BedrockProfileState.profileEntries(commit.carry()).stream().anyMatch(entry ->
                    entry.state().physicalFeetPosition().subtract(BedrockVectorAdapter.toBedrock(frame.getPosition())).length() <= positionLimit
                    && entry.state().velocity().subtract(BedrockVectorAdapter.toBedrock(frame.getReportedEndOfTickVelocity())).length() <= velocityLimit);
        if (converged && pending != null && pending.received && frame.getClientTick() > pending.correction.tick()
                && player.getSetbackTeleportUtil().completeBedrockMovementCorrection(pending.correction)) {
            pending = null;
            rewind.converged();
        }
        if (frame.getClientTick() == 0 || requestedTransaction < 0 && (converged
                || pending != null && (!pending.received || processedTicks - pending.sentAt < CORRECTION_INTERVAL_TICKS))) return;
        int transaction = requestedTransaction >= 0 ? requestedTransaction
                : pending == null ? -1 : pending.correction.teleportTransaction();
        var correction = new BedrockMovementCorrection(0, generation, vehicle == null ? -1 : vehicle.getEntityId(), runtimeId,
                frame.getClientTick(), BedrockVectorAdapter.toJava(state.physicalFeetPosition()),
                BedrockVectorAdapter.toJava(state.velocity()), state.inputFrame().yaw(), state.inputFrame().pitch(),
                state.collisionFlags().onGround(), state.coordinateFrame(), transaction,
                state.isBoat() ? state.boat().angularVelocity() : null, state.isVehicle());
        pending = new Pending(correction, processedTicks);
        if (GeyserBedrockBridgeRuntime.sendMovementCorrection(player.user, correction)) requestedTransaction = -1;
        else pending = null;
    }

    public void clear() {
        generation++;
        rewind.clear();
        lastTick = -1;
        processedTicks = 0;
        controlled = null;
        runtimeId = Long.MIN_VALUE;
        pending = null;
        requestedTransaction = -1;
    }

    private static final class Pending {
        final BedrockMovementCorrection correction;
        final long sentAt;
        boolean received;
        Pending(BedrockMovementCorrection correction, long sentAt) { this.correction = correction; this.sentAt = sentAt; }
    }
}
