package ac.cult.cultac.manager.player.setback;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.bedrock.protocol.BedrockMovementCorrection;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.data.TeleportAcceptData;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.data.VehicleTeleportData;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

/**
 * Vehicle teleports and their answers. ClientPacketListener#handleMoveVehicle answers a move_vehicle for the vehicle
 * the client controls with its exact position, or does not move it within its 1.0E-5 no-snap window; a vehicle the
 * client does not control needs no answer.
 *
 * <ul>
 *   <li>Only the packet handler's echo completes a vehicle teleport, never a ticked move.
 *   <li>A Java client's echo completes a Cult vehicle correction only after it answers the ping sent before it.
 *   <li>A Cult vehicle correction the client can no longer answer, because it left the vehicle, moves the player
 *       instead, with no vehicle velocity.
 *   <li>A Bedrock vehicle correction interrupted by a dismount moves the player to where it rode the corrected
 *       vehicle.
 * </ul>
 */
public final class VehicleTeleportTracker {
    private final CultPlayer player;
    private final CorrectionState corrections;
    private final SafePositionTracker safePositions;
    private final CorrectionReissue reissue;
    private final Supplier<BedrockCoordinateFrame> bedrockFrame;

    public VehicleTeleportTracker(
            CultPlayer player,
            CorrectionState corrections,
            SafePositionTracker safePositions,
            CorrectionReissue reissue,
            Supplier<BedrockCoordinateFrame> bedrockFrame) {
        this.player = player;
        this.corrections = corrections;
        this.safePositions = safePositions;
        this.reissue = reissue;
        this.bedrockFrame = bedrockFrame;
    }

    private final ConcurrentLinkedQueue<VehicleTeleport> vehicleTeleports = new ConcurrentLinkedQueue<>();
    // A required vehicle setback whose vehicle teleport was dropped, kept until the client is off the vehicle.
    private SetBackData droppedVehicleSetback = null;
    private volatile BedrockVehicleSetback bedrockVehicleSetback;

    /**
     * @param x - Player X position
     * @param y - Player Y position
     * @param z - Player Z position
     * @return - Whether the player has completed a teleport by being at this position
     */
    public TeleportAcceptData checkVehicleTeleportQueue(Integer vehicleId, double x, double y, double z) {
        return checkVehicleTeleportQueue(vehicleId, x, y, z, false);
    }

    /**
     * @param tickMove whether LocalPlayer#sendChanges sent the packet after the rider's Rot, which no
     *     ClientPacketListener response is
     */
    public TeleportAcceptData checkVehicleTeleportQueue(
            Integer vehicleId, double x, double y, double z, boolean tickMove) {
        TeleportAcceptData acceptData = new TeleportAcceptData();

        int lastTransaction = player.lastTransactionReceived.get();

        while (true) {
            VehicleTeleport teleportPos = vehicleTeleports.peek();
            if (teleportPos == null) break;
            // Bedrock corrections complete after rewind prediction, without a Java position echo.
            if (teleportPos.rewind()) break;
            if (vehicleId != null && teleportPos.vehicleId() != vehicleId) {
                if (lastTransaction >= teleportPos.transaction()) {
                    final VehicleTeleport staleEntry = vehicleTeleports.poll();
                    continue;
                }
                break;
            }

            Vec3 position = teleportPos.position();

            if (reissue.isDebug()) {
                LogUtil.info("Vehicle queue: id: " + teleportPos.vehicleId() + " pt: " + teleportPos.transaction()
                        + " lt: " + lastTransaction + " | x=" + (position.x - x) + " y=" + (position.y - y) + " z="
                        + (position.z - z));
            }

            // A Cult correction's move_vehicle follows its ping, and ClientPacketListener#handleMoveVehicle answers it
            // from the handler, so a Java client answers it only after that ping. Geyser moves a Bedrock rider's
            // vehicle itself, outside the client's ping order.
            if (!player.isBedrockMovement()
                    && isRequiredSetbackVehicleTeleport(teleportPos)
                    && lastTransaction < teleportPos.transaction()) {
                break;
            }

            if (matchesVehicleTeleportPosition(position, x, y, z, teleportPos.responseDistanceTolerance())) {
                if (!tickMove) {
                    final VehicleTeleport matchedEntry = vehicleTeleports.poll();
                    return completeVehicleTeleport(teleportPos);
                }
                // A ticking boat interpolates onto a TELEPORT_ENTITY target and the client sends no response for it.
                if (teleportPos.state() != null
                        && !isRequiredSetbackVehicleTeleport(teleportPos)
                        && lastTransaction >= teleportPos.transaction()) {
                    vehicleTeleports.poll();
                    continue;
                }
            }

            // MCP-Reborn sends exact MoveVehicle responses from the packet
            // handler for local-authoritative vehicle teleports. A coordinate
            // match above is proof; otherwise wait for transaction ordering.
            if (lastTransaction < teleportPos.transaction()) {
                break;
            } else if (lastTransaction > teleportPos.transaction() + 1) {
                boolean ignoredRequiredSetbackVehicleTeleport = isRequiredSetbackVehicleTeleport(teleportPos);
                final VehicleTeleport ignoredEntry = vehicleTeleports.poll();

                if (ignoredRequiredSetbackVehicleTeleport) {
                    reissue.resendIgnored("vehicle-position-ignored");
                }

                // Vehicles have terrible netcode so just ignore it if the teleport wasn't from us setting the player
                // back
                // Players don't have to respond to vehicle teleports if they aren't controlling the entity anyways
                continue;
            }

            break;
        }

        return acceptData;
    }

    private TeleportAcceptData completeVehicleTeleport(VehicleTeleport teleport) {
        TeleportAcceptData accepted = new TeleportAcceptData();
        TeleportData data =
                new TeleportData(teleport.position(), new RelativeFlag(0), player.lastTransactionReceived.get(), 0);
        if (reissue.isDebug()) {
            LogUtil.info("Vehicle queue accepted (size: " + vehicleTeleports.size() + ")");
        }
        SetBackData required = corrections.required();
        if (required != null && required.getTeleportData().getTransaction() == teleport.transaction()) {
            if (reissue.isDebug()) {
                LogUtil.info("Removed vehicle blockOffsets, previous=" + corrections.isBlockingOffsets());
            }
            corrections.complete(required);
            accepted.setSetback(required);
            data = required.getTeleportData();
        }
        accepted.setTeleportData(data);
        accepted.setVehicleTeleportData(teleport.state());
        accepted.setTeleport(true);
        return accepted;
    }

    private boolean matchesVehicleTeleportPosition(
            Vec3 position, double x, double y, double z, double responseDistanceTolerance) {
        return (matchesVehicleTeleportCoordinate(position.x, x)
                        && matchesVehicleTeleportCoordinate(position.y, y)
                        && matchesVehicleTeleportCoordinate(position.z, z))
                || matchesVehicleTeleportDistance(position, x, y, z, responseDistanceTolerance);
    }

    private boolean matchesVehicleTeleportCoordinate(double expected, double actual) {
        if (!Double.isFinite(expected) || !Double.isFinite(actual)) {
            return false;
        }

        // Cult sometimes reconstructs the same vanilla vehicle coordinate from
        // its collision-box shadow. One ULP covers that arithmetic representation
        // drift without accepting any measurable movement.
        return expected == actual || Math.abs(expected - actual) <= Math.max(Math.ulp(expected), Math.ulp(actual));
    }

    private boolean matchesVehicleTeleportDistance(Vec3 expected, double x, double y, double z, double tolerance) {
        if (tolerance <= 0.0D || !Double.isFinite(tolerance)) {
            return false;
        }
        if (!Double.isFinite(expected.x)
                || !Double.isFinite(expected.y)
                || !Double.isFinite(expected.z)
                || !Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(z)) {
            return false;
        }
        // MCP-Reborn ClientPacketListener#handleMoveVehicle only snaps when the
        // packet is farther than 1.0E-5 from the current interpolation position.
        // The queued response for that clientbound packet is therefore bounded to
        // the same no-snap radius; other vehicle teleports pass zero tolerance.
        return expected.distanceTo(new Vec3(x, y, z)) <= tolerance;
    }

    private boolean isRequiredSetbackVehicleTeleport(VehicleTeleport teleport) {
        return corrections.isAwaiting(teleport.transaction());
    }

    public void addVehicleTeleport(int vehicleId, int transaction, Vec3 position) {
        addVehicleTeleport(vehicleId, transaction, position, null);
    }

    public void addVehicleTeleport(int vehicleId, int transaction, Vec3 position, double responseDistanceTolerance) {
        addVehicleTeleport(vehicleId, transaction, position, null, responseDistanceTolerance);
    }

    public void addVehicleTeleport(int vehicleId, int transaction, Vec3 position, VehicleTeleportData state) {
        addVehicleTeleport(vehicleId, transaction, position, state, 0.0D);
    }

    public void addVehicleTeleport(
            int vehicleId,
            int transaction,
            Vec3 position,
            VehicleTeleportData state,
            double responseDistanceTolerance) {
        if (player.isBedrockMovement()
                && ac.cult.cultac.bedrock.prediction.integration.BedrockVehicleControl.isSupported(
                        player.compensatedEntities.getEntity(vehicleId))) {
            Vec3 local = bedrockFrame.get().toLocal(position);
            position = bedrockFrame.get().toWorld(new Vec3((float) local.x, (float) local.y, (float) local.z));
            responseDistanceTolerance = 0.0D;
        }
        vehicleTeleports.add(
                new VehicleTeleport(vehicleId, transaction, position, state, responseDistanceTolerance, false));
    }

    public void addBedrockVehicleTeleport(int vehicleId, int transaction, Vec3 position) {
        vehicleTeleports.add(new VehicleTeleport(vehicleId, transaction, position, null, 0.0D, true));
    }

    /** Receipt alone cannot complete a rewind. Called only after movement from the corrected state passes. */
    public boolean completeBedrockMovementCorrection(BedrockMovementCorrection correction) {
        if (!player.isBedrockMovement()) return false;
        if (correction.teleportTransaction() < 0) return true;
        if (player.lastTransactionReceived.get() < correction.teleportTransaction()) return false;
        for (VehicleTeleport teleport : vehicleTeleports) {
            // A refresh targets the current simulated tick, not the original teleport position.
            if (teleport.rewind()
                    && teleport.vehicleId() == correction.vehicleId()
                    && teleport.transaction() == correction.teleportTransaction()
                    && vehicleTeleports.remove(teleport)) {
                completeVehicleTeleport(teleport);
                return true;
            }
        }
        // A repeated correction can refer to a teleport already completed by an earlier packet.
        SetBackData required = corrections.required();
        return required == null
                || required.isComplete()
                || required.getTeleportData().getTransaction() != correction.teleportTransaction();
    }

    public void clearVehicleTeleports() {
        if (!player.isBedrockMovement() && vehicleTeleports.stream().anyMatch(this::isRequiredSetbackVehicleTeleport)) {
            droppedVehicleSetback = corrections.required();
        }
        vehicleTeleports.clear();
        if (player.bedrockState != null) {
            player.runSafely(() -> player.bedrockState.movementCorrections.clear());
        }
        reissueDroppedVehicleSetback();
    }

    /**
     * A client off the corrected vehicle never answers its move, so a dropped vehicle setback moves the player
     * instead. Called where the client-visible graph leaves a vehicle.
     */
    public void reissueDroppedVehicleSetback() {
        SetBackData dropped = droppedVehicleSetback;
        if (dropped == null || player.getRidingVehicleId() != Integer.MIN_VALUE) return;
        droppedVehicleSetback = null;
        // A respawn places the player anew.
        if (corrections.required() != dropped
                || dropped.isComplete()
                || dropped.isPlugin()
                || safePositions.current() == null) return;
        if (CultAPI.INSTANCE.getConfigManager().isObserveOnly()) {
            reissue.release(new Vec3(player.x, player.y, player.z));
            return;
        }
        // Entity#rideTick zeroes a passenger's delta movement every ride tick and Entity#removeVehicle leaves it, so
        // the player leaves the vehicle without the vehicle's velocity.
        dropped.setVelocity(Vec3.ZERO);
        reissue.sendDismounted(dropped);
    }

    public void transferBedrockVehicleSetback(int previousVehicleId) {
        BedrockVehicleSetback pending = bedrockVehicleSetback;
        if (pending == null
                || pending.vehicleId() != previousVehicleId
                || corrections.required() != pending.setback()
                || !corrections.isPending()
                || player.compensatedEntities.getSelf().inVehicle()) return;
        Integer serverVehicle = player.compensatedEntities.vehicles.serverPlayerVehicle;
        if (serverVehicle != null && player.compensatedEntities.vehicles.isServerPlayerPassengerOf(serverVehicle))
            return;

        clearVehicleTeleports();
        SetBackData previous = pending.setback();
        SetBackData replacement = new SetBackData(
                new TeleportData(pending.ridingPosition(), new RelativeFlag(0b11000), 0, 0),
                previous.getXRot(),
                previous.getYRot(),
                Vec3.ZERO,
                false,
                false,
                false);
        reissue.sendDismounted(replacement);
    }

    private record BedrockVehicleSetback(SetBackData setback, int vehicleId, Vec3 ridingPosition) {}

    private record VehicleTeleport(
            int vehicleId,
            int transaction,
            Vec3 position,
            VehicleTeleportData state,
            double responseDistanceTolerance,
            boolean rewind) {}

    public boolean hasUnacknowledgedSetbackVehicleTeleport() {
        SetBackData required = corrections.required();
        if (required == null || required.isPlugin() || required.isComplete()) {
            return false;
        }

        for (VehicleTeleport teleport : vehicleTeleports) {
            if (isRequiredSetbackVehicleTeleport(teleport)
                    && player.lastTransactionReceived.get() < teleport.transaction()) {
                return true;
            }
        }
        return false;
    }

    public int queuedVehicleTeleportCount() {
        return vehicleTeleports.size();
    }
    /** A Bedrock vehicle correction was sent; a dismount before its answer moves the player to its seat instead. */
    public void bedrockSetbackSent(SetBackData setback, int vehicleId, Vec3 ridingPosition) {
        bedrockVehicleSetback = new BedrockVehicleSetback(setback, vehicleId, ridingPosition);
    }

    public void clearBedrockVehicleSetback() {
        bedrockVehicleSetback = null;
    }

    /** Forgets, when run, the vehicle teleports queued now. */
    public Runnable forgetQueued() {
        List<VehicleTeleport> earlierVehicles = List.copyOf(vehicleTeleports);
        return () -> vehicleTeleports.removeAll(earlierVehicles);
    }
}
