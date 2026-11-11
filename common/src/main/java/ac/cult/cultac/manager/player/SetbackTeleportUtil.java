package ac.cult.cultac.manager.player;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.manager.player.setback.BedrockTeleportTracker;
import ac.cult.cultac.manager.player.setback.CorrectionBuilder;
import ac.cult.cultac.manager.player.setback.CorrectionReissue;
import ac.cult.cultac.manager.player.setback.CorrectionSender;
import ac.cult.cultac.manager.player.setback.CorrectionState;
import ac.cult.cultac.manager.player.setback.JavaTeleportTracker;
import ac.cult.cultac.manager.player.setback.MovementGate;
import ac.cult.cultac.manager.player.setback.SafePositionCapture;
import ac.cult.cultac.manager.player.setback.SafePositionTracker;
import ac.cult.cultac.manager.player.setback.SafeProof;
import ac.cult.cultac.manager.player.setback.SetbackEntity;
import ac.cult.cultac.manager.player.setback.SetbackPolicy;
import ac.cult.cultac.manager.player.setback.SetbackReason;
import ac.cult.cultac.manager.player.setback.TeleportAnswers;
import ac.cult.cultac.manager.player.setback.VehicleTeleportTracker;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.data.*;
import ac.cult.cultac.utils.math.Vec3;
import java.util.concurrent.ConcurrentLinkedDeque;
import lombok.Getter;

public class SetbackTeleportUtil extends CultProcessor
        implements PostPredictionListener, SafePositionTracker.Corrections, MovementGate.Teleports, CorrectionReissue {

    // Sync to netty
    public final ConcurrentLinkedDeque<TeleportData> pendingTeleports = new ConcurrentLinkedDeque<>();

    private final CorrectionState corrections = new CorrectionState();
    private final SafePositionTracker safePositions = new SafePositionTracker(player, this);
    private final MovementGate gate = new MovementGate(player, corrections, this);
    private final CorrectionBuilder builder = new CorrectionBuilder(player);
    private final SafePositionCapture capture = new SafePositionCapture(player, safePositions, corrections);
    private final TeleportAnswers answers = new TeleportAnswers(player, gate, safePositions, corrections);
    private final VehicleTeleportTracker vehicles = new VehicleTeleportTracker(
            player, corrections, safePositions, this, () -> this.bedrock.getActiveBedrockCoordinateFrame());
    private final BedrockTeleportTracker bedrock =
            new BedrockTeleportTracker(player, pendingTeleports, corrections, gate, answers, vehicles);
    private final JavaTeleportTracker javaTeleports =
            new JavaTeleportTracker(player, pendingTeleports, corrections, safePositions, answers, this);
    private final CorrectionSender sender = new CorrectionSender(player, corrections, javaTeleports, bedrock, vehicles);
    private final SetbackPolicy policy = new SetbackPolicy(
            player, safePositions, corrections, gate, builder, sender, bedrock, vehicles, this::isDebug);

    @Getter
    private boolean debug = false;

    /** The packets a correction is sent with. */
    public CorrectionSender sender() {
        return sender;
    }

    public SetbackTeleportUtil(CultPlayer player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        capture.capture(predictionComplete);
    }
    /** The entity a setback moves now. */
    @Override
    public SetbackEntity setbackEntity() {
        return SetbackEntity.current(player);
    }

    /** The position, velocity and state a setback returns the player to; null before the player is placed. */
    public SetbackPosWithVector safePosition() {
        return safePositions.current();
    }

    /** Whether the client accepted the teleport that placed it after joining or respawning. */
    public boolean hasFullyLoaded() {
        return gate.hasFullyLoaded();
    }

    /** The Java client read its respawn; its new LocalPlayer waits for the placement teleport. */
    public void respawnRead() {
        gate.respawnRead();
    }

    /** Whether offsets stay blocked until the client accepts the full-resync setback. */
    public boolean isBlockingOffsets() {
        return corrections.isBlockingOffsets();
    }

    /** The prediction desynced while a setback is pending: block offsets until the client accepts it. */
    public void blockOffsetsUntilSetbackAccepted() {
        corrections.blockOffsets();
    }

    /** Whether Cult is writing its own setback packets right now. */
    public boolean isSendingSetback() {
        return corrections.isSending();
    }

    void setLoaded(boolean loaded) {
        gate.setLoaded(loaded);
    }

    void setBlockOffsets(boolean block) {
        if (block) corrections.blockOffsets();
        else corrections.unblockOffsets();
    }

    /** Test fixtures put a correction in flight without sending it. */
    void requireCorrection(SetBackData data) {
        corrections.require(data);
    }

    /** Test fixtures place the player at a known safe position without simulating how it got there. */
    void seedSafePosition(SetbackPosWithVector safe) {
        safePositions.write(safe, SafePositionTracker.Write.TEST_SEED);
    }

    /**
     * Scales the safe velocity a following setback sends. The elytra sprint-jump config rejects the sprint-jump boost
     * the safe tick took.
     */
    public void scaleSafeVelocity(Vec3 factor) {
        safePositions.scaleVelocity(factor);
    }

    @Override
    public boolean safePositionUnblocked() {
        SetBackData required = corrections.required();
        return required == null || required.isComplete() && !corrections.isBlockingOffsets() && !insideUnloadedChunk();
    }

    /** The vehicle runner resolves the root's post-tick ground after the prediction staged this safe position. */
    public void setStagedHorseOnGround(DesyncStatus onGround) {
        safePositions.setStagedHorseOnGround(onGround);
    }

    /**
     * Commits the safe position staged by this prediction pass once every listener on it has run. A Java movement
     * packet holds it until its receive handlers have run too.
     */
    public void commitSafePosition() {
        safePositions.commit();
    }

    public void holdSafePosition() {
        safePositions.hold();
    }

    public void releaseSafePosition() {
        safePositions.release();
    }

    /** The client's movement for a tick arrived. Until a safe position includes it, a setback rejects it. */
    public void movementReceived() {
        safePositions.movementReceived();
    }

    public void executeForceResync(String cause) {
        policy.executeForceResync(cause);
    }

    public void executeNonSimulatingSetback() {
        policy.executeNonSimulatingSetback();
    }

    public boolean executeViolationSetback() {
        return policy.executeViolationSetback();
    }

    /** A setback for a violation of {@code check}, null when no check of the caller raised it. */
    public boolean executeViolationSetback(Check check) {
        return policy.executeViolationSetback(check);
    }

    /** A setback for the rejected {@code flagged} movement; a ridden vehicle takes the tick simulated for it. */
    public boolean executeViolationSetback(PredictionResult flagged) {
        return policy.executeViolationSetback(flagged);
    }

    /** As {@link #executeViolationSetback(PredictionResult)}, for a violation of {@code check}, or null. */
    public boolean executeViolationSetback(PredictionResult flagged, Check check) {
        return policy.executeViolationSetback(flagged, check);
    }

    public boolean executeTooHighLatencySetback(String cause) {
        return policy.executeTooHighLatencySetback(cause);
    }

    /**
     * While the client is in configuration it has no level: no setback is computed, and the next play login places
     * the player afresh. Minecraft#clearClientLevel discards the player a required setback was meant to move.
     */
    public void levelLeft() {
        safePositions.clear();
        gate.levelLeft();
        corrections.drop();
        player.checkManager.getSimulationProcessor().getLastMovementTime().clear();
    }

    /**
     * A respawn discards the player a setback corrected; the server's placement teleport positions the new one and
     * movement stays blocked until the client accepts it.
     */
    public void respawnSent() {
        safePositions.clear();
        gate.respawnSent(!player.isBedrockMovement());
        corrections.unblockOffsets();
        // Latency is measured from the next movement of the placed player, not the last one before it died.
        player.checkManager.getSimulationProcessor().getLastMovementTime().clear();
    }

    /**
     * A Java client accepts each teleport before it acknowledges the configuration that precedes a LOGIN, so a
     * teleport still pending at LOGIN send was withheld. It can no longer be accepted, and the new backend reuses its
     * ID.
     */
    public void loginSent() {
        javaTeleports.loginSent();
    }

    @Override
    public void resendIgnored(String reason) {
        policy.resendIgnoredRequiredSetback(reason);
    }

    @Override
    public void release(Vec3 position) {
        policy.releaseRequiredSetback(position);
    }

    @Override
    public boolean hasUnacknowledgedSetbackVehicleTeleport() {
        return vehicles.hasUnacknowledgedSetbackVehicleTeleport();
    }

    @Override
    public void sendDismounted(SetBackData data) {
        sender.sendSetback(data, false, true, SetbackReason.RESEND, "dismounted", null);
    }

    /**
     * @return If the player is in a desync state and is waiting on information from the server
     */
    public boolean shouldBlockMovement() {
        return shouldBlockMovement(false);
    }

    /** A corrected vehicle must still be checked before its pending setback can complete. */
    public boolean shouldBlockMovement(boolean validatingVehicleCorrection) {
        return gate.shouldBlockMovement(validatingVehicleCorrection);
    }

    public boolean shouldBlockVehicleMovement() {
        return shouldBlockVehicleMovement(false);
    }

    /** A validated auth frame cannot authorize another Java movement while its correction is pending. */
    public boolean blocksBedrockTranslatedMovement() {
        return gate.blocksBedrockTranslatedMovement();
    }

    public boolean shouldBlockVehicleMovement(boolean simulatedCorrection) {
        return gate.shouldBlockVehicleMovement(simulatedCorrection);
    }

    public String getDebugStrings() {
        SetBackData required = corrections.required();
        final String setbackState = required == null
                ? "null"
                : !required.isComplete() + " x: " + player.x + ", y: " + player.y + " z: " + player.z;
        return "tooFar: " + tooFarFromUnloadedChunk() + ", insideUnloadedChunk: " + insideUnloadedChunk()
                + ", hasFullyLoaded: " + gate.hasFullyLoaded() + ", blockOffsets: " + corrections.isBlockingOffsets()
                + ", requiredSetBack: " + setbackState;
    }

    @Override
    public boolean isPendingSetback() {
        return corrections.isPending();
    }

    /** Whether the player is in an unloaded chunk far enough from the correction that it cannot be the reason. */
    public boolean tooFarFromUnloadedChunk() {
        return gate.tooFarFromUnloadedChunk();
    }

    /** Whether the player is in a chunk the client has not loaded, where its movement is not checked. */
    public boolean insideUnloadedChunk() {
        return gate.insideUnloadedChunk();
    }

    /**
     * @return The current data for the setback, regardless of whether it is complete or not
     */
    public SetBackData getRequiredSetBack() {
        return corrections.required();
    }

    /** The server or Cult placed the vehicle {@code vehicleId} at {@code pos} after the ping {@code transaction}. */
    public void updateSafeVehiclePosition(int vehicleId, int transaction, Vec3 pos) {
        safePositions.offer(
                new SetbackPosWithVector(pos, Vec3.ZERO)
                        .provenBy(SetbackEntity.of(vehicleId), SafeProof.placement(transaction)),
                SafePositionTracker.Write.VEHICLE_PLACEMENT);
    }

    /**
     * The server moves the vehicle {@code vehicleId} the player does not control, and the client is proven to have it
     * at {@code pos}: a placement by the server, not a movement of the client's.
     */
    public void proveSafeVehiclePosition(int vehicleId, Vec3 pos) {
        safePositions.offer(
                new SetbackPosWithVector(pos, Vec3.ZERO)
                        .provenBy(
                                SetbackEntity.of(vehicleId), SafeProof.placement(player.lastTransactionReceived.get())),
                SafePositionTracker.Write.VEHICLE_PLACEMENT);
    }

    @Override
    public boolean mustAcknowledgeBedrockTransportTeleport() {
        return bedrock.mustAcknowledgeBedrockTransportTeleport();
    }
    /** The Java client's answers to player_position and rotation teleports. */
    public JavaTeleportTracker javaTeleports() {
        return javaTeleports;
    }

    /** Bedrock transport teleports and their coordinate frames. */
    public BedrockTeleportTracker bedrockTeleports() {
        return bedrock;
    }

    /** Vehicle teleports and their answers. */
    public VehicleTeleportTracker vehicleTeleports() {
        return vehicles;
    }

    /** When a setback is sent, and the freeze watchdog. */
    public SetbackPolicy policy() {
        return policy;
    }

    @Override
    public void reload() {
        super.reload();
        policy.setFreeze(getConfig().getIntElse("cult.prediction.max-freeze-time-ms", 5000));
        this.debug = getConfig().getBooleanElse("cult.diagnostics.teleports", false);
    }
}
