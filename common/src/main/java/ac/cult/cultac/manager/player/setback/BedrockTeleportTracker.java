package ac.cult.cultac.manager.player.setback;

import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.bedrock.protocol.BedrockMoveFrame;
import ac.cult.cultac.bedrock.protocol.BedrockTeleportOperation;
import ac.cult.cultac.bedrock.protocol.BedrockTeleportProvenance;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.data.TeleportAcceptData;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.VectorUtils;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;

/**
 * Bedrock transport teleports: every position Geyser or Cult emits to the Bedrock client, with its coordinate frame,
 * and the HANDLE_TELEPORT answers that prove the client took them.
 *
 * <ul>
 *   <li>A teleport completes only on the exact HANDLE_TELEPORT input after its receipt and its origin are proven, and
 *       that answer moves the active frame.
 *   <li>A link that seats the player before the client handles a teleport puts the rider on its seat: the client
 *       handles the teleport on its first tick from that vehicle, which travels on.
 *   <li>Input coordinates resolve from the active frame or a proven echo, never from the input's own claim.
 *   <li>Only a Cult-owned emission can complete a Cult correction.
 *   <li>Paper's last accepted position is kept for the corrections that must return there.
 * </ul>
 */
public final class BedrockTeleportTracker {
    private final CultPlayer player;
    // Sync to netty, shared with the Java answers.
    private final ConcurrentLinkedDeque<TeleportData> pendingTeleports;
    private final CorrectionState corrections;
    private final MovementGate gate;
    private final TeleportAnswers answers;
    private final VehicleTeleportTracker vehicles;

    public BedrockTeleportTracker(
            CultPlayer player,
            ConcurrentLinkedDeque<TeleportData> pendingTeleports,
            CorrectionState corrections,
            MovementGate gate,
            TeleportAnswers answers,
            VehicleTeleportTracker vehicles) {
        this.player = player;
        this.pendingTeleports = pendingTeleports;
        this.corrections = corrections;
        this.gate = gate;
        this.answers = answers;
        this.vehicles = vehicles;
    }

    private BedrockCoordinateFrame activeBedrockCoordinateFrame = BedrockCoordinateFrame.IDENTITY;

    public BedrockCoordinateFrame getActiveBedrockCoordinateFrame() {
        return activeBedrockCoordinateFrame;
    }

    private final AtomicLong bedrockTeleportRevision = new AtomicLong();

    public long getBedrockTeleportRevision() {
        return bedrockTeleportRevision.get();
    }
    // The position of the last Bedrock movement packet actually forwarded to
    // Paper (Geyser's Java projection or a teleport echo), captured at the
    // packet level after all cancellation decisions. Never derived from
    // engine/canonical state, so it always equals Paper's lastGood* values.
    private Vec3 bedrockPaperVisiblePosition;

    public void setBedrockPaperVisiblePosition(Vec3 position) {
        this.bedrockPaperVisiblePosition = position;
    }

    /** Resolve only from an already active origin or an emitted, transaction-proven exact local echo. */
    public BedrockAuthInputFrame resolveBedrockCoordinates(BedrockAuthInputFrame input) {
        if (!player.playerUUID.equals(input.getPlayerUuid())) return null;
        BedrockCoordinateFrame frame = activeBedrockCoordinateFrame;
        if (input.hasRawInputFlag(PlayerAuthInputData.HANDLE_TELEPORT)) {
            for (TeleportData pending : pendingTeleports) {
                if (isConfirmedBedrockTeleport(pending)
                        && sameLocalPacket(pending.getBedrockLocalPacketTarget(), input.getPacketPosition())) {
                    frame = pending.getBedrockCoordinateFrame();
                }
            }
        }
        if (!input.hasCoordinateProvenance() && !frame.equals(BedrockCoordinateFrame.IDENTITY)) return null;
        // The bridge submits unresolved identity frames. Nonidentity provenance (e.g. a replay) must agree
        // with transport evidence; it can never establish a frame by itself.
        if (!input.getCoordinateFrame().equals(BedrockCoordinateFrame.IDENTITY)
                && !input.getCoordinateFrame().equals(frame)) return null;
        return input.resolveCoordinates(frame);
    }

    public BedrockMoveFrame resolveBedrockCoordinates(BedrockMoveFrame input) {
        if (!player.playerUUID.equals(input.playerUuid())) return null;
        if (!input.coordinateProvenance() && !activeBedrockCoordinateFrame.equals(BedrockCoordinateFrame.IDENTITY))
            return null;
        if (!input.coordinateFrame().equals(BedrockCoordinateFrame.IDENTITY)
                && !input.coordinateFrame().equals(activeBedrockCoordinateFrame)) return null;
        return input.resolveCoordinates(activeBedrockCoordinateFrame);
    }

    private static boolean sameLocalPacket(Vec3 target, Vec3 actual) {
        return target != null
                && actual != null
                && (float) target.x == (float) actual.x
                && (float) target.y == (float) actual.y
                && (float) target.z == (float) actual.z;
    }

    private boolean isConfirmedBedrockTeleport(TeleportData pending) {
        return !pending.isRotationOnly()
                && pending.getBedrockTransportRevision() >= 0
                && (pending.isBedrockOriginConfirmed()
                        || pending.getBedrockCoordinateFrame().equals(activeBedrockCoordinateFrame))
                && pending.getBedrockCoordinateFrame().revision() >= activeBedrockCoordinateFrame.revision()
                && player.lastTransactionReceived.get() >= pending.getTransaction();
    }

    public TeleportAcceptData acknowledgeBedrockTeleportFrame(BedrockAuthInputFrame frame) {
        return acknowledgeBedrockTeleportFrame(
                frame.getPosition(), frame.hasRawInputFlag(PlayerAuthInputData.HANDLE_TELEPORT), frame);
    }

    public TeleportAcceptData acknowledgeBedrockTeleportFrame(Vec3 physicalFeetPosition) {
        return acknowledgeBedrockTeleportFrame(physicalFeetPosition, true);
    }

    public TeleportAcceptData acknowledgeBedrockTeleportFrame(Vec3 physicalFeetPosition, boolean handlesTeleport) {
        return acknowledgeBedrockTeleportFrame(physicalFeetPosition, handlesTeleport, null);
    }

    private TeleportAcceptData acknowledgeBedrockTeleportFrame(
            Vec3 physicalFeetPosition, boolean handlesTeleport, BedrockAuthInputFrame frame) {
        TeleportAcceptData accepted = new TeleportAcceptData();
        if (!player.isBedrockMovement() || physicalFeetPosition == null || !handlesTeleport) {
            return accepted;
        }

        Vec3 actual = VectorUtils.clampVector(physicalFeetPosition);
        TeleportData matching = null;
        for (TeleportData pending : pendingTeleports) {
            if (!isConfirmedBedrockTeleport(pending)
                    || !(frame == null
                            ? pending.getBedrockCoordinateFrame().equals(BedrockCoordinateFrame.IDENTITY)
                            : pending.getBedrockCoordinateFrame().equals(frame.getCoordinateFrame()))
                    || !answersTeleport(pending, actual, frame)) continue;
            // One HANDLE_TELEPORT acknowledges the first identical outbound
            // teleport and every later one whose receipt came back before
            // this input: the client took those before the tick. Reliable
            // packet order proves that every earlier identical boundary was
            // processed first, so retaining those entries would wait for
            // acknowledgements Geyser consumes and never projects to Java.
            // A later identical teleport whose receipt is still out is
            // answered by an input of its own.
            if (matching != null && !pending.isBedrockOriginConfirmed()) break;
            matching = pending;
        }
        if (matching == null) {
            return accepted;
        }

        // An acknowledged teleport supersedes earlier Geyser and GFP targets.
        // Cult setbacks remain pending until their own validated response.
        long matchedRevision = matching.getBedrockTransportRevision();
        pendingTeleports.removeIf(pending -> !pending.mayCompleteCultSetback()
                && pending.getBedrockTransportRevision() >= 0L
                && pending.getBedrockTransportRevision() < matchedRevision);

        if (!pendingTeleports.remove(matching)) {
            return accepted;
        }

        // The Bedrock receipt and exact HANDLE_TELEPORT frame prove this boundary.
        activeBedrockCoordinateFrame = matching.getBedrockCoordinateFrame();
        // A seated rider's frame reports its vehicle: the teleport's own position is where the server put the player.
        return matching.isBedrockSeated()
                ? answers.complete(matching, VectorUtils.clampVector(matching.getLocation()), false)
                : answers.complete(matching, actual, true);
    }

    /** Whether a HANDLE_TELEPORT input at {@code actual} answers {@code pending}. */
    private static boolean answersTeleport(TeleportData pending, Vec3 actual, BedrockAuthInputFrame frame) {
        // The rider's seat, not the teleport, places it: the first input from the seating vehicle answers.
        if (pending.isBedrockSeated()) {
            return frame != null
                    && frame.hasRawInputFlag(PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE)
                    && frame.getPredictedVehicleId() != null
                    && frame.getPredictedVehicleId() == pending.getBedrockSeatVehicle();
        }
        return pending.getBedrockLocalPacketTarget() == null || frame == null
                ? samePosition(VectorUtils.clampVector(pending.getLocation()), actual)
                : sameLocalPacket(pending.getBedrockLocalPacketTarget(), frame.getPacketPosition());
    }

    /**
     * The client handles a link that seats the player on the vehicle {@code vehicleRuntimeId}. The teleports written
     * before it that the client has not handled yet stay pending on that seat.
     */
    public Runnable captureBedrockVehicleMount(long vehicleRuntimeId) {
        long revision = bedrockTeleportRevision.get();
        SetBackData setback = corrections.required();
        int transaction = setback == null ? -1 : setback.getTeleportData().getTransaction();
        Runnable forgetEarlierVehicles = vehicles.forgetQueued();
        long correctionGeneration = player.bedrockState.movementCorrections.generation();
        return () -> {
            pendingTeleports.removeIf(pending -> {
                boolean preceding =
                        pending.getBedrockTransportRevision() >= 0 && pending.getBedrockTransportRevision() <= revision;
                boolean owned =
                        setback != null && pending.getTransaction() == transaction && pending.mayCompleteCultSetback();
                if (!preceding && !owned) return false;
                if (preceding
                        && pending.getBedrockCoordinateFrame().revision() >= activeBedrockCoordinateFrame.revision()) {
                    activeBedrockCoordinateFrame = pending.getBedrockCoordinateFrame();
                }
                if (owned) return true;
                pending.setBedrockSeatVehicle(vehicleRuntimeId);
                return false;
            });
            forgetEarlierVehicles.run();
            if (setback != null
                    && corrections.required() == setback
                    && setback.getTeleportData().getTransaction() == transaction) {
                corrections.complete(setback);
                vehicles.clearBedrockVehicleSetback();
            }
            gate.setLoaded(true);
            // A link that seats the player again on the vehicle it predicts leaves the client's corrections of it.
            if (corrections.required() == setback
                    && (setback == null || setback.getTeleportData().getTransaction() == transaction)
                    && player.bedrockState.movementCorrections.generation() == correctionGeneration
                    && !player.bedrockState.movementCorrections.predicts(vehicleRuntimeId)) {
                player.bedrockState.movementCorrections.clear();
            }
        };
    }

    public boolean hasPendingBedrockTransportTeleport() {
        return pendingTeleports.stream()
                .anyMatch(pending -> !pending.isRotationOnly() && pending.getBedrockTransportRevision() >= 0L);
    }

    public boolean isBedrockSetbackTransport(long revision) {
        SetBackData required = corrections.required();
        if (required == null || required.isPlugin()) return false;
        return pendingTeleports.stream()
                .anyMatch(pending -> pending.getBedrockTransportRevision() == revision
                        && pending.mayCompleteCultSetback()
                        && corrections.isAwaiting(pending.getTransaction()));
    }

    public boolean isCurrentBedrockSetback(int transaction) {
        SetBackData required = corrections.required();
        return player.isBedrockMovement()
                && required != null
                && !required.isPlugin()
                && !required.isComplete()
                && required.getTeleportData().getTransaction() == transaction;
    }

    public boolean isPendingBedrockSetback(BedrockTeleportOperation operation) {
        return operation != null
                && operation.provenance() == BedrockTeleportProvenance.CULT_SETBACK
                && operation.setbackTransaction() != null
                && isCurrentBedrockSetback(operation.setbackTransaction())
                && pendingTeleports.stream().anyMatch(pending -> operation.equals(pending.getBedrockOperation()));
    }

    /**
     * The pending Geyser or GFP position teleport with transport revision {@code revision} that only a HANDLE_TELEPORT
     * answers, or null. Cult setbacks, seats and dimension changes resolve elsewhere.
     */
    public TeleportData unansweredBedrockTransport(long revision) {
        for (TeleportData pending : pendingTeleports) {
            if (pending.getBedrockTransportRevision() == revision
                    && revision >= 0L
                    && !pending.isRotationOnly()
                    && !pending.mayCompleteCultSetback()
                    && !pending.isBedrockSeated()
                    && !pending.isBedrockDimensionChange()) return pending;
        }
        return null;
    }

    public boolean mustAcknowledgeBedrockTransportTeleport() {
        return pendingTeleports.stream()
                .anyMatch(pending -> !pending.isRotationOnly()
                        && pending.getBedrockTransportRevision() >= 0L
                        && (pending.getBedrockProvenance() != BedrockTeleportProvenance.GFP_REBASE
                                || pending.isBedrockOriginConfirmed()
                                        && player.lastTransactionReceived.get() >= pending.getTransaction()));
    }

    private static boolean samePosition(Vec3 first, Vec3 second) {
        return Math.abs(first.x - second.x) <= BEDROCK_WIRE_POSITION_EPSILON
                && Math.abs(first.y - second.y) <= BEDROCK_WIRE_POSITION_EPSILON
                && Math.abs(first.z - second.z) <= BEDROCK_WIRE_POSITION_EPSILON;
    }

    // The Bedrock wire position is float32 with Geyser's 1.62f eye offset while
    // the bridge canonicalizes through the double 1.6200103759765625 constant,
    // so the same physical position can surface ~2e-5 apart. The match window
    // only needs to exclude a client that did not adopt the teleport at all.
    private static final double BEDROCK_WIRE_POSITION_EPSILON = 1.0E-4D;

    public long addImmediateBedrockTransportTeleport(Vec3 physicalFeetPosition, boolean onGround) {
        return addImmediateBedrockTransportTeleport(
                physicalFeetPosition,
                onGround,
                BedrockCoordinateFrame.IDENTITY,
                null,
                null,
                player.lastTransactionSent.get());
    }

    public long addImmediateBedrockTransportTeleport(
            Vec3 physicalFeetPosition,
            boolean onGround,
            BedrockCoordinateFrame frame,
            Vec3 localPacketTarget,
            BedrockTeleportOperation operation,
            int proofTransaction) {
        long revision = bedrockTeleportRevision.incrementAndGet();
        // Keep the complete boundary on the owning packet executor before its receipt
        // or later auth input, including callers outside the local Geyser bridge.
        player.runSafely(() -> registerBedrockTransportTeleport(
                revision, physicalFeetPosition, onGround, frame, localPacketTarget, operation, proofTransaction));
        return revision;
    }

    private void registerBedrockTransportTeleport(
            long revision,
            Vec3 physicalFeetPosition,
            boolean onGround,
            BedrockCoordinateFrame frame,
            Vec3 localPacketTarget,
            BedrockTeleportOperation operation,
            int proofTransaction) {
        BedrockTeleportProvenance provenance =
                operation == null ? BedrockTeleportProvenance.GEYSER : operation.provenance();
        Integer setbackTransaction = operation == null ? null : operation.setbackTransaction();
        Vec3 clamped = VectorUtils.clampVector(physicalFeetPosition);
        if (provenance == BedrockTeleportProvenance.CULT_SETBACK) {
            if (setbackTransaction == null || !isCurrentBedrockSetback(setbackTransaction)) return;
            pendingTeleports.removeIf(
                    pending -> pending.getBedrockProvenance() == BedrockTeleportProvenance.CULT_SETBACK
                            && !operation.equals(pending.getBedrockOperation()));
        }
        // A retry is another emission of the same operation, not a new transport-only move.
        if (operation != null) {
            for (TeleportData pending : pendingTeleports) {
                if (operation.equals(pending.getBedrockOperation())) {
                    boolean confirmed = pending.isBedrockOriginConfirmed()
                            && sameBedrockDestination(pending, frame, localPacketTarget);
                    pending.applyBedrockTransportBoundary(clamped, onGround, revision);
                    applyBedrockOrigin(pending, frame, localPacketTarget);
                    if (confirmed) pending.setBedrockOriginConfirmed(true);
                    return;
                }
            }
        }
        // Only a Cult-owned native emission may satisfy a pending Cult setback.
        SetBackData required = corrections.required();
        if (provenance == BedrockTeleportProvenance.CULT_SETBACK
                && required != null
                && !required.isComplete()
                && setbackTransaction != null
                && required.getTeleportData().getTransaction() == setbackTransaction) {
            TeleportData requiredTeleport = required.getTeleportData();
            TeleportData data = requiredTeleport.copyWithLocation(clamped);
            data.applyBedrockTransportBoundary(clamped, onGround, revision);
            data.setBedrockOperation(operation);
            applyBedrockOrigin(data, frame, localPacketTarget);
            pendingTeleports.add(data);
            return;
        }

        TeleportData data = new TeleportData(clamped, new RelativeFlag(0), Vec3.ZERO, proofTransaction, 0);
        data.setPositionOnly(true);
        data.setBedrockOperation(operation);
        data.setBedrockOnGround(onGround);
        data.setBedrockTransportRevision(revision);
        applyBedrockOrigin(data, frame, localPacketTarget);
        pendingTeleports.add(data);
    }

    public void confirmBedrockOrigin(
            long transportRevision,
            BedrockTeleportOperation operation,
            BedrockCoordinateFrame frame,
            Vec3 localPacketTarget) {
        for (TeleportData pending : pendingTeleports) {
            // A receipt for an earlier identical emission still proves this operation's origin.
            // Operation identity alone is insufficient if a later emission changed its destination.
            if ((pending.getBedrockTransportRevision() == transportRevision
                            || operation != null && operation.equals(pending.getBedrockOperation()))
                    && sameBedrockDestination(pending, frame, localPacketTarget)) {
                pending.setBedrockOriginConfirmed(true);
            }
        }
    }

    private static boolean sameBedrockDestination(
            TeleportData data, BedrockCoordinateFrame frame, Vec3 localPacketTarget) {
        return data.getBedrockCoordinateFrame().equals(frame)
                && java.util.Objects.equals(data.getBedrockLocalPacketTarget(), localPacketTarget);
    }

    private static void applyBedrockOrigin(TeleportData data, BedrockCoordinateFrame frame, Vec3 localPacketTarget) {
        data.setBedrockOriginConfirmed(localPacketTarget == null);
        data.setBedrockCoordinateFrame(frame);
        data.setBedrockLocalPacketTarget(localPacketTarget);
    }
    /** The position of the last Bedrock movement Paper accepted, or null. */
    public Vec3 paperVisiblePosition() {
        return bedrockPaperVisiblePosition;
    }
}
