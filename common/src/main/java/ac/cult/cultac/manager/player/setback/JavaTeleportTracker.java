package ac.cult.cultac.manager.player.setback;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsB;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsN;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.anticheat.NumFormatter;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.data.SetbackPosWithVector;
import ac.cult.cultac.utils.data.TeleportAcceptData;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.VectorUtils;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * The Java client's answers to player_position and rotation teleports. ClientPacketListener#handleMovePlayer answers
 * each player_position with its ID before the client handles the next ping, so the queue is matched in send order by
 * ID, position and transaction.
 *
 * <ul>
 *   <li>An answer matches the teleport's ID and its exact target; the exact target, not the client's report, is what
 *       the server is told.
 *   <li>A teleport the client got past was ignored: BadPacketsN flags it, and the correction is sent again once
 *       nothing newer is queued.
 *   <li>A passenger's answer leaves its position, so it completes only a teleport sent while mounted and never a Cult
 *       correction.
 *   <li>A ping answered past an unanswered ID teleport shows a client withholding its answer.
 *   <li>A relative teleport resolves against the safe position; a server teleport freezes it until answered.
 * </ul>
 */
public final class JavaTeleportTracker {
    private final CultPlayer player;
    // Sync to netty
    private final ConcurrentLinkedDeque<TeleportData> pendingTeleports;
    private final CorrectionState corrections;
    private final SafePositionTracker safePositions;
    private final TeleportAnswers answers;
    private final CorrectionReissue reissue;

    public JavaTeleportTracker(
            CultPlayer player,
            ConcurrentLinkedDeque<TeleportData> pendingTeleports,
            CorrectionState corrections,
            SafePositionTracker safePositions,
            TeleportAnswers answers,
            CorrectionReissue reissue) {
        this.player = player;
        this.pendingTeleports = pendingTeleports;
        this.corrections = corrections;
        this.safePositions = safePositions;
        this.answers = answers;
        this.reissue = reissue;
    }

    /**
     * A Java client accepts each teleport before it acknowledges the configuration that precedes a LOGIN, so a
     * teleport still pending at LOGIN send was withheld. It can no longer be accepted, and the new backend reuses its
     * ID.
     */
    public void loginSent() {
        pendingTeleports.removeIf(JavaTeleportTracker::isJavaIdTeleport);
    }

    private static final float ROTATION_TELEPORT_EPSILON = 1.0E-4F;
    /** The teleport whose ID a pre-26.3 client sent last, until the PosRot that must follow it. */
    private TeleportData awaitingPosRot;

    /**
     * @param x - Player X position
     * @param y - Player Y position
     * @param z - Player Z position
     * @return - Whether the player has completed a teleport by being at this position
     */
    public TeleportAcceptData checkTeleportQueue(double px, double py, double pz) {
        return checkTeleportQueue(new Vec3(px, py, pz), null, false);
    }

    public TeleportAcceptData checkExactJavaTeleportQueue(double px, double py, double pz, int teleportId) {
        return checkTeleportQueue(new Vec3(px, py, pz), teleportId, true);
    }

    /** Entity-to-self teleports have no ID acknowledgement; normal player teleports do. */
    public boolean hasIdlessJavaPositionTeleport() {
        for (TeleportData pending : pendingTeleports) {
            if (!pending.isRotationOnly()) return pending.isPositionOnly();
        }
        return false;
    }

    private TeleportAcceptData checkTeleportQueue(
            Vec3 physicalFeetPosition, Integer teleportId, boolean exactPosition) {
        // Support teleports without teleport confirmations
        // If the player is in a vehicle when teleported, they will exit their vehicle
        TeleportAcceptData teleportData = new TeleportAcceptData();
        if (teleportId != null
                && pendingTeleports.stream()
                        .noneMatch(pending -> !pending.isRotationOnly()
                                && pending.getBedrockTransportRevision() < 0L
                                && pending.getTeleportId() == teleportId)) {
            return teleportData;
        }

        TeleportData teleportPos;
        while ((teleportPos = pendingTeleports.peek()) != null) {
            if (teleportPos.isRotationOnly()) {
                if (player.lastTransactionReceived.get() >= teleportPos.getTransaction()) {
                    pendingTeleports.poll();
                    continue;
                }
                break;
            }

            if (teleportPos.getBedrockTransportRevision() >= 0L) {
                // Bedrock transport entries are completed only by the exact raw
                // auth-input echo after their normal Cult transaction proof. A
                // translated Java move is not a second acknowledgement channel.
                break;
            }

            int receivedTransaction = player.lastTransactionReceived.get();
            if (teleportId != null && teleportPos.getTeleportId() != teleportId) {
                if (receivedTransaction > teleportPos.getTransaction()) {
                    pendingTeleports.poll();
                    continue;
                }
                break;
            }
            // ClientPacketListener#handleMovePlayer leaves passengers in place.
            // Consume its single response at the same transaction boundary as a
            // normal teleport, without applying the reported passenger position.
            if (!player.isBedrockMovement()
                    && receivedTransaction == teleportPos.getTransaction()
                    && teleportPos.isSentWhileVehicle()
                    && !teleportPos.isSentDuringVehicleDismount()
                    && player.compensatedEntities.getSelf().inVehicle()) {
                pendingTeleports.poll();
                return completeMountedTeleport(teleportPos);
            }

            Vec3 shove = teleportPos.isRelativeX() || teleportPos.isRelativeY() || teleportPos.isRelativeZ()
                    ? player.compensatedWorld.lastTickShulkerBoxShove()
                    : Vec3.ZERO;
            double trueTeleportX = (teleportPos.isRelativeX() ? player.x + shove.x : 0) + teleportPos.getLocation().x;
            double trueTeleportY = (teleportPos.isRelativeY() ? player.y + shove.y : 0) + teleportPos.getLocation().y;
            double trueTeleportZ = (teleportPos.isRelativeZ() ? player.z + shove.z : 0) + teleportPos.getLocation().z;

            // There seems to be a version difference in teleports past 30 million... just clamp the vector
            // I'm not sure how teleports work on 26.1
            Vec3 clamped = VectorUtils.clampVector(new Vec3(trueTeleportX, trueTeleportY, trueTeleportZ));

            // There was some previous claims that rounding was performed here, I see no evidence of this.
            // TODO: Investigate vehicle teleports working for players too on 26.1?

            double xDiff = Math.abs(clamped.x - physicalFeetPosition.x);
            double yDiff = Math.abs(clamped.y - physicalFeetPosition.y);
            double zDiff = Math.abs(clamped.z - physicalFeetPosition.z);

            // idk why mojang just doesn't set the movement threshold to 0, but I'm not fighting over 0.0002
            boolean xPass =
                    xDiff <= (exactPosition ? 0 : teleportPos.isRelativeX() ? player.getMovementThreshold() : 0);
            boolean yPass =
                    yDiff <= (exactPosition ? 0 : teleportPos.isRelativeY() ? player.getMovementThreshold() : 0);
            boolean zPass =
                    zDiff <= (exactPosition ? 0 : teleportPos.isRelativeZ() ? player.getMovementThreshold() : 0);

            if (reissue.isDebug())
                LogUtil.info("dx=" + xDiff + " dy=" + yDiff + " dz=" + zDiff + " | trans=" + receivedTransaction + " | "
                        + teleportPos.getTransaction());

            boolean exactTeleportPosition = xPass && yPass && zPass;
            if (exactTeleportPosition
                    && (teleportPos.isPositionOnly() || receivedTransaction == teleportPos.getTransaction())) {
                pendingTeleports.poll();
                player.compensatedWorld.onClientPositionSet();
                return answers.complete(teleportPos, clamped, true);
            } else if (teleportPos.isPositionOnly() && receivedTransaction <= teleportPos.getTransaction()) {
                break;
            } else if (teleportPos.isPositionOnly() || receivedTransaction > teleportPos.getTransaction()) {
                if (reissue.isDebug()) {
                    LogUtil.info("TP ignored: xd=" + xDiff + " yd=" + yDiff + " zd=" + zDiff);
                }

                boolean currentRequiredSetback = corrections.isRequired(teleportPos.getTransaction());
                boolean vehicleTransitionCanIgnoreTeleport =
                        teleportPos.isSentWhileVehicle() && isVehicleTransitionTeleportContext();

                // MCP-Reborn ClientPacketListener#handleMovePlayer always
                // confirms the teleport id, but only applies the position when
                // minecraft.player is not a passenger. A mounted confirmation
                // is therefore valid protocol, but it is not proof that a
                // Cult-owned setback position was applied.
                if (!vehicleTransitionCanIgnoreTeleport) {
                    final String teleportDiffs = "xd=" + NumFormatter.formatNumberStandard(xDiff) + " yd="
                            + NumFormatter.formatNumberStandard(yDiff) + " zd="
                            + NumFormatter.formatNumberStandard(zDiff);
                    player.checkManager.getListener(BadPacketsN.class).flag(teleportDiffs);
                }
                pendingTeleports.poll();
                if (currentRequiredSetback) {
                    if (CultAPI.INSTANCE.getConfigManager().isObserveOnly()) {
                        reissue.release(physicalFeetPosition);
                    } else {
                        corrections.required().setPlugin(false);
                    }
                }
                if (!vehicleTransitionCanIgnoreTeleport
                        && pendingTeleports.isEmpty()
                        && corrections.required() != null) {
                    reissue.resendIgnored("player-position-ignored");
                }
                continue;
            }
            // No farther setbacks before the player's transactoin
            break;
        }

        return teleportData;
    }

    public TeleportAcceptData checkRotationTeleportQueue(float yaw, float pitch) {
        TeleportAcceptData teleportData = new TeleportAcceptData();

        TeleportData teleportPos;
        while ((teleportPos = pendingTeleports.peek()) != null) {
            if (!teleportPos.isRotationOnly()) {
                return teleportData;
            }

            // The first Rot after a ping that handleRotatePlayer's bundle task answered first is its echo.
            TeleportData resolved = player.lastTransactionReceived.get() >= teleportPos.getBundleTransaction()
                    ? teleportPos.resolvePlayerRotationEcho(yaw, pitch, player.xRot, player.yRot)
                    : teleportPos.resolvePlayerRotation(player.xRot, player.yRot);
            if (matchesRotationTeleport(resolved, yaw, pitch)) {
                pendingTeleports.poll();
                teleportData.setTeleportData(resolved.copyWithLocation(new Vec3(player.x, player.y, player.z)));
                teleportData.setTeleport(true);
                return teleportData;
            }

            if (player.lastTransactionReceived.get() >= teleportPos.getTransaction()) {
                pendingTeleports.poll();
                continue;
            }

            return teleportData;
        }

        return teleportData;
    }

    private static boolean matchesRotationTeleport(TeleportData teleportPos, float yaw, float pitch) {
        return Math.abs(teleportPos.getFinalYaw() - yaw) <= ROTATION_TELEPORT_EPSILON
                && Math.abs(teleportPos.getFinalPitch() - pitch) <= ROTATION_TELEPORT_EPSILON;
    }

    private boolean isVehicleTransitionTeleportContext() {
        return player.compensatedEntities.vehicles.serverPlayerVehicle != null
                || player.compensatedEntities.getSelf().inVehicle();
    }

    private TeleportAcceptData completeMountedTeleport(TeleportData teleport) {
        Vec3 position = new Vec3(player.x, player.y, player.z);
        if (!isRequiredSetbackTeleport(teleport) || corrections.required().isPlugin()) {
            return answers.complete(teleport, position, false);
        }

        // A passenger ignores the position. This cannot complete a Cult setback.
        corrections.required().setPlugin(false);
        reissue.resendIgnored("mounted-player-position-ignored");
        TeleportAcceptData accepted = new TeleportAcceptData();
        accepted.setTeleportData(teleport.copyWithLocation(position));
        accepted.setTeleport(true);
        return accepted;
    }

    private boolean isRequiredSetbackTeleport(TeleportData teleport) {
        return corrections.isAwaiting(teleport.getTransaction());
    }

    public boolean hasPendingPlayerPositionTeleport() {
        TeleportData teleport = pendingTeleports.peek();
        return teleport != null && !teleport.isRotationOnly();
    }

    public void addSentTeleport(Vec3 position, int transaction, RelativeFlag flags, boolean plugin, int teleportId) {
        addSentTeleport(position, Vec3.ZERO, transaction, flags, plugin, teleportId);
    }

    public void addSentTeleport(
            Vec3 position, Vec3 deltaMovement, int transaction, RelativeFlag flags, boolean plugin, int teleportId) {
        addSentTeleport(position, deltaMovement, transaction, flags, plugin, teleportId, 0.0F, 0.0F, 0.0F, 0.0F);
    }

    public void addSentTeleport(
            Vec3 position,
            Vec3 deltaMovement,
            int transaction,
            RelativeFlag flags,
            boolean plugin,
            int teleportId,
            float sourceYaw,
            float sourcePitch,
            float finalYaw,
            float finalPitch) {
        TeleportData data = new TeleportData(
                position, flags, deltaMovement, transaction, teleportId, sourceYaw, sourcePitch, finalYaw, finalPitch);
        data.setSentWhileVehicle(player.compensatedEntities.vehicles.serverPlayerVehicle != null
                || player.compensatedEntities.getSelf().inVehicle());
        data.setSentDuringVehicleDismount(player.compensatedEntities.vehicles.hasPendingServerDismount());

        // We must convert relative teleports to avoid them becoming client controlled in the case of setback. Before
        // the placement that follows a login or respawn, the client's new LocalPlayer starts where no safe position
        // knows; its answer is matched against Cult's last position, and the placement gives the safe position.
        boolean relative = flags.isSet(RelativeFlag.X.getMask())
                || flags.isSet(RelativeFlag.Y.getMask())
                || flags.isSet(RelativeFlag.Z.getMask());
        SetbackPosWithVector base = safePositions.current();
        Vec3 from = base == null ? new Vec3(player.x, player.y, player.z) : base.getPos();
        Vec3 safePosition = new Vec3(
                flags.isSet(RelativeFlag.X.getMask()) ? position.x + from.x : position.x,
                flags.isSet(RelativeFlag.Y.getMask()) ? position.y + from.y : position.y,
                flags.isSet(RelativeFlag.Z.getMask()) ? position.z + from.z : position.z);

        pendingTeleports.add(data);

        data = data.copyWithLocation(safePosition);
        corrections.require(new SetBackData(data, player.xRot, player.yRot, null, false, plugin));
        if (relative && base == null) return;

        // Cult's own correction sets the client's delta movement absolutely (PositionMoveRotation#calculateAbsolute); a
        // server teleport's, or a relative delta, leaves a velocity only the client's answer proves.
        boolean absoluteDelta = !data.isRelativeDeltaX() && !data.isRelativeDeltaY() && !data.isRelativeDeltaZ();
        safePositions.offer(
                new SetbackPosWithVector(safePosition, !plugin && absoluteDelta ? deltaMovement : Vec3.ZERO)
                        .provenBy(SetbackEntity.PLAYER, SafeProof.placement(transaction)),
                SafePositionTracker.Write.PLACEMENT);
    }

    /**
     * ClientPacketListener#handleMovePlayer answers a teleport with its ID, carrying the position from 26.3 on and
     * followed by a PosRot before.
     */
    public void teleportAnswered(int teleportId, boolean carriesPosition) {
        awaitingPosRot = null;
        for (TeleportData pending : pendingTeleports) {
            if (isJavaIdTeleport(pending) && !pending.isAnswered() && pending.getTeleportId() == teleportId) {
                if (carriesPosition) {
                    pending.setAnswered(true);
                } else {
                    awaitingPosRot = pending;
                }
                return;
            }
        }
    }

    /** A PosRot from a client whose teleport answers carry no position. */
    public void posRotReceived() {
        if (awaitingPosRot != null) {
            awaitingPosRot.setAnswered(true);
            awaitingPosRot = null;
        }
    }

    /**
     * Whether the client handled a ping sent after a player_position teleport without answering it.
     * ClientPacketListener#handleMovePlayer sends its answer before the client handles the next ping, so a vanilla
     * client never does.
     */
    public boolean hasWithheldJavaTeleport() {
        if (pendingTeleports.isEmpty()) return false;
        int received = player.lastTransactionReceived.get();
        for (TeleportData pending : pendingTeleports) {
            if (isJavaIdTeleport(pending) && !pending.isAnswered() && received > pending.getTransaction()) return true;
        }
        return false;
    }

    /** A player_position teleport, which the Java client accepts by ID. */
    private static boolean isJavaIdTeleport(TeleportData pending) {
        return !pending.isRotationOnly() && !pending.isPositionOnly() && pending.getBedrockTransportRevision() < 0L;
    }

    public void addImmediatePlayerTeleport(
            Vec3 position,
            Vec3 deltaMovement,
            RelativeFlag flags,
            int proofTransaction,
            float sourceYaw,
            float sourcePitch,
            float finalYaw,
            float finalPitch) {
        // ClientboundTeleportEntityPacket has no teleport id. When the vanilla
        // client remaps a removed vehicle teleport onto the player, the following
        // PosRot is the only acknowledgement. Accept the exact echo immediately,
        // but keep the trailing proof transaction to detect an ignored echo.
        int transaction = proofTransaction < 0 ? Integer.MAX_VALUE : proofTransaction;
        TeleportData data = new TeleportData(
                position, flags, deltaMovement, transaction, 0, sourceYaw, sourcePitch, finalYaw, finalPitch);
        data.setPositionOnly(true);
        pendingTeleports.addFirst(data);
    }

    public void addImmediatePlayerRotationTeleport(
            RelativeFlag flags,
            int bundleTransaction,
            int proofTransaction,
            float sourceYaw,
            float sourcePitch,
            float finalYaw,
            float finalPitch,
            ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerRotation rotation) {
        int transaction = proofTransaction < 0 ? Integer.MAX_VALUE : proofTransaction;
        TeleportData data = new TeleportData(
                Vec3.ZERO, flags, Vec3.ZERO, transaction, 0, sourceYaw, sourcePitch, finalYaw, finalPitch);
        data.setRotationOnly(true);
        data.setPlayerRotation(rotation);
        data.setBundleTransaction(bundleTransaction < 0 ? Integer.MAX_VALUE : bundleTransaction);
        pendingTeleports.add(data);
        if (proofTransaction >= 0) {
            player.latencyUtils.addRealTimeTask(transaction, () -> {
                // A matching Rot response removes this entry in
                // checkRotationTeleportQueue. If it is still pending after
                // the trailing transaction, the client processed the forced
                // rotation without acknowledging it. This is the NMS-port
                // equivalent of the old pendingRotations BadPacketsB call.
                if (pendingTeleports.remove(data)) {
                    player.checkManager.getCheck(BadPacketsB.class).flag();
                }
            });
        }
    }
}
