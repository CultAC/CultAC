package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.bridge.GeyserBedrockBridgeRuntime;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockMovementCorrection;
import ac.cult.cultac.checks.impl.prediction.PredictionCommit;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.PredictionSetbackState;
import ac.cult.cultac.checks.impl.prediction.SuperDebug;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import java.util.function.Consumer;

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
    private boolean exemptMovement;
    private int requestedTransaction = -1;
    // Where the server last held the vehicle, and the generation that placement began.
    private Vec3 serverPosition;
    private long serverPositionGeneration = -1;
    // Where the queued attribute updates go if the queue is dropped before a frame consumes them.
    private Consumer<BedrockReplayAttributeEvent> queuedAttributeCarry;

    public long generation() {
        return generation;
    }

    public boolean hasPendingCorrection() {
        return pending != null || requestedTransaction >= 0;
    }

    public void request(
            CultPlayer player,
            int vehicleId,
            Vec3 position,
            Vec3 velocity,
            float yaw,
            float pitch,
            boolean onGround,
            int transaction) {
        request(player, vehicleId, position, velocity, yaw, pitch, onGround, transaction, null);
    }

    public void request(
            CultPlayer player,
            int vehicleId,
            Vec3 position,
            Vec3 velocity,
            float yaw,
            float pitch,
            boolean onGround,
            int transaction,
            PredictionSetbackState setback) {
        var vehicle = BedrockVehicleControl.controlledVehicle(player);
        if (vehicle == null || vehicle.getEntityId() != vehicleId) return;
        player.checkManager
                .getListener(ac.cult.cultac.checks.impl.bedrock.BedrockMovement.class)
                .onVehicleTeleport();
        clear();
        player.checkManager
                .getSimulationProcessor()
                .resetBedrockVehicle(vehicle, position, velocity, yaw, pitch, onGround, setback);
        requestedTransaction = transaction;
        pending = null;
    }

    /**
     * The server rejected a move of {@code vehicleId} and holds it at {@code position}. Only the position is the
     * server's: the vehicle keeps its velocity, rotation and ground state, and the next frame corrects the client to
     * the result. It raises no vehicle teleport, so it opens no window in which movement goes unjudged.
     */
    public void requestServerPosition(CultPlayer player, int vehicleId, Vec3 position, int transaction) {
        var vehicle = BedrockVehicleControl.controlledVehicle(player);
        if (vehicle == null || vehicle.getEntityId() != vehicleId) return;
        // A setback places the vehicle itself, and the server answers no move until it completes.
        if (player.getSetbackTeleportUtil().isPendingSetback()) return;
        // The server answers every move it rejects. Until a frame moves the vehicle from the position, a repeat of it
        // answers a move sent before the vehicle was placed there.
        if (serverPositionGeneration == generation && processedTicks == 0 && position.equals(serverPosition)) return;
        clear();
        player.checkManager
                .getSimulationProcessor()
                .resetBedrockVehicle(
                        vehicle,
                        position,
                        vehicle.deltaMovement,
                        vehicle.clientPhysicalYaw,
                        vehicle.clientPhysicalPitch,
                        vehicle.onGround,
                        null);
        requestedTransaction = transaction;
        serverPosition = position;
        serverPositionGeneration = generation;
    }

    public void requestSetback(int vehicleId, int transaction) {
        if (controlled != null && controlled.getEntityId() == vehicleId) requestedTransaction = transaction;
    }

    public void observe(CultPlayer player, BedrockMovementCorrection correction) {
        if (exemptMovement && correction.teleportTransaction() < 0) return;
        if (correction.controlGeneration() != generation
                || runtimeId == Long.MIN_VALUE
                || correction.vehicle() != (controlled != null)
                || controlled != null
                        && (controlled.getEntityId() != correction.vehicleId() || runtimeId != correction.runtimeId()))
            return;
        pending = new Pending(correction, processedTicks);
    }

    public void acknowledge(long sequence) {
        if (pending != null && pending.correction.sequence() == sequence && !pending.received) {
            pending.received = true;
            if (!exemptMovement)
                rewind.queue(pending.correction.tick(), true, new BedrockReplayEvent.Transform(pending.correction));
        }
    }

    public void beginFrame(CultPlayer player, long tick, int vehicleId, long actorRuntimeId) {
        var vehicle = BedrockVehicleControl.controlledVehicle(player);
        if (vehicle != null && vehicle.getEntityId() == vehicleId) bind(vehicle, actorRuntimeId);
    }

    public void queueUpdate(
            long expectedGeneration,
            int vehicleId,
            long actorRuntimeId,
            long tick,
            boolean historical,
            BedrockReplayEvent event) {
        if (expectedGeneration != generation) return;
        if (vehicleId < 0
                ? controlled != null
                : controlled == null || controlled.getEntityId() != vehicleId || runtimeId != actorRuntimeId) return;
        rewind.queue(tick, historical, event);
    }

    public BedrockReplayEvent.Metadata metadata(long expectedGeneration, long tick, BedrockReplayEvent.Metadata event) {
        return metadata(expectedGeneration, tick, event, 0);
    }

    public BedrockReplayEvent.Metadata metadata(
            long expectedGeneration, long tick, BedrockReplayEvent.Metadata event, int flagWords) {
        if (expectedGeneration != generation) return null;
        return controlled == null ? rewind.metadata(tick, event, flagWords) : event;
    }

    /** Attribute lifetime follows the actor, not the currently mounted vehicle or correction generation. */
    public void attributes(CultPlayer player, PacketEntity entity, long tick, BedrockReplayAttributeEvent event) {
        boolean self = entity == player.compensatedEntities.getSelf();
        var active = BedrockVehicleControl.controlledVehicle(player);
        if ((self && active == null || entity == active) && (runtimeId == Long.MIN_VALUE || controlled == active)) {
            rewind.queue(tick, event.historical(), event);
            queuedAttributeCarry = self ? queued -> applyToPlayer(player, queued) : queued -> apply(entity, queued);
        } else if (self) {
            player.checkManager.getSimulationProcessor().applyInactiveBedrockAttributes(event);
        } else {
            apply(entity, event);
        }
    }

    private static void applyToPlayer(CultPlayer player, BedrockReplayAttributeEvent event) {
        var processor = player.checkManager.getSimulationProcessor();
        var state = BedrockProfileState.previousState(
                processor.getCurrentPredictionCommit().carry());
        // During a vehicle frame the processor holds the vehicle's carry, never the player's.
        if (state != null && !state.isVehicle()) processor.applyInactiveBedrockAttributes(event);
    }

    private static void apply(PacketEntity entity, BedrockReplayAttributeEvent event) {
        var previous = entity.bedrockPrediction;
        if (previous == null) return;
        entity.bedrockPrediction = new BedrockVehiclePredictionState(
                ((BedrockReplayAttributeEvent) event.ordinary()).apply(previous.commit()),
                previous.previousPrediction());
    }

    public PredictionCommit prepareFrame(CultPlayer player, BedrockAuthInputFrame frame, PredictionCommit current) {
        var vehicle = BedrockVehicleControl.controlledVehicle(player);
        bind(vehicle, vehicle == null ? -1 : frame.getPredictedVehicleId());
        return rewind.apply(player, current);
    }

    public BedrockSimulation.Input prepareInput(BedrockSimulation.Input input) {
        return rewind.prepareInput(input);
    }

    public Vec3 predictionStart(Vec3 fallback) {
        return rewind.predictionStart(fallback);
    }

    private void bind(PacketEntity vehicle, long actorRuntimeId) {
        if (runtimeId != Long.MIN_VALUE && (vehicle != controlled || runtimeId != actorRuntimeId)) clear();
        controlled = vehicle;
        runtimeId = actorRuntimeId;
    }

    public void record(
            CultPlayer player,
            BedrockAuthInputFrame frame,
            PacketEntity vehicle,
            PredictionResult result,
            PredictionCommit commit) {
        bind(vehicle, vehicle == null ? -1 : frame.getPredictedVehicleId());
        if (commit == null || frame.getClientTick() <= lastTick) return;
        exemptMovement = result != null && result.isExempt();
        if (exemptMovement) {
            rewind.clear();
            if (pending != null && pending.correction.teleportTransaction() < 0) pending = null;
            lastTick = frame.getClientTick();
            processedTicks++;
            return;
        }
        if (result != null) rewind.record(player, result, commit);
        lastTick = frame.getClientTick();
        processedTicks++;
        var state = BedrockProfileState.previousState(commit.carry());
        if (state == null) return;
        if (player.getSetbackTeleportUtil().bedrockTeleports().hasPendingBedrockTransportTeleport()) return;
        double positionLimit = CultAPI.INSTANCE.getConfigManager().getBedrockMovementPositionFlagThreshold();
        double velocityLimit = CultAPI.INSTANCE.getConfigManager().getBedrockMovementVelocityFlagThreshold();
        boolean converged = result != null
                && result.getFlags().isEmpty()
                && frame.getReportedEndOfTickVelocity() != null
                && BedrockProfileState.profileEntries(commit.carry()).stream()
                        .anyMatch(entry -> entry.state()
                                                .physicalFeetPosition()
                                                .subtract(BedrockVectorAdapter.toBedrock(frame.getPosition()))
                                                .length()
                                        <= positionLimit
                                && entry.state()
                                                .velocity()
                                                .subtract(BedrockVectorAdapter.toBedrock(
                                                        frame.getReportedEndOfTickVelocity()))
                                                .length()
                                        <= velocityLimit);
        if (converged
                && pending != null
                && pending.received
                && frame.getClientTick() > pending.correction.tick()
                && player.getSetbackTeleportUtil()
                        .vehicleTeleports()
                        .completeBedrockMovementCorrection(pending.correction)) {
            pending = null;
        }
        if (frame.getClientTick() == 0
                || requestedTransaction < 0
                        && (converged
                                || pending != null
                                        && (!pending.received
                                                || processedTicks - pending.sentAt < CORRECTION_INTERVAL_TICKS)))
            return;
        int transaction = requestedTransaction >= 0
                ? requestedTransaction
                : pending == null ? -1 : pending.correction.teleportTransaction();
        var correction = new BedrockMovementCorrection(
                0,
                generation,
                vehicle == null ? -1 : vehicle.getEntityId(),
                runtimeId,
                frame.getClientTick(),
                BedrockVectorAdapter.toJava(state.physicalFeetPosition()),
                BedrockVectorAdapter.toJava(state.velocity()),
                state.inputFrame().yaw(),
                state.inputFrame().pitch(),
                state.collisionFlags().onGround(),
                state.coordinateFrame(),
                transaction,
                state.isBoat() ? state.boat().angularVelocity() : null,
                state.isVehicle());
        pending = new Pending(correction, processedTicks);
        int debugId = player.checkManager.getDebugHandler().hasRewindListeners()
                ? SuperDebug.ensureBedrockCorrectionLog(player, result)
                : 0;
        if (GeyserBedrockBridgeRuntime.sendMovementCorrection(player.user, correction, frame.getPosition(), debugId))
            requestedTransaction = -1;
        else pending = null;
    }

    public void clear() {
        exemptMovement = false;
        generation++;
        // The client keeps an acknowledged attribute whatever resets this history; so must the actor's carry.
        if (queuedAttributeCarry != null) rewind.drainAttributes().forEach(queuedAttributeCarry);
        queuedAttributeCarry = null;
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

        Pending(BedrockMovementCorrection correction, long sentAt) {
            this.correction = correction;
            this.sentAt = sentAt;
        }
    }
}
