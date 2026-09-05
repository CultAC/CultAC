package ac.cult.cultac.checks.impl.prediction.runner;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.checks.EngineCheck;
import ac.cult.cultac.checks.impl.prediction.profile.MovementProfiles;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.anticheat.NumFormatter;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.data.TransactionVel;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import org.bukkit.GameMode;
import net.minecraft.world.phys.Vec3;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

@ToString
public class PacketModHandler extends Check implements EngineCheck, PostPredictionListener {
    double offsetToFlag;
    double maxAdvantage, immediate, ceiling, multiplier;
    @Getter double threshold;

    public transient List<TransactionVel> overriddenVels = new ArrayList<>();
    public TransactionVel firstBread = null;
    public transient TransactionVel secondBread = null;
    @Getter
    public transient TransactionVel lastSent = null;

    @Getter
    boolean canTickSkip = false;
    @Setter
    boolean isSetbackVal = false;

    public PacketModHandler(CultPlayer cultPlayer, CheckInfo info) { super(cultPlayer, info); }

    @Override
    public void handleResult(CultPlayer player, PredictionResult result, PredictionResult lastResult) {
        if (firstBread == null && secondBread == null) return;

        // The player could have skipped this velocity
        boolean isPointThree = result.getInitialStartingVel().isKnockback() && result.getSimulationContext().isTestingPointThree() && result.getOffset() < player.getMovementThreshold();

        if (isPointThree) {
            canTickSkip = true;
        }
        if (result.getSimulationContext().isTestingPointThree()) return;

        double flags = result.getFlagSeverity();

        if (result.getInitialStartingVel().hasPacketModifier(secondBread)) {
            secondBread.addOffset(flags);
        } else if (result.getInitialStartingVel().hasPacketModifier(firstBread)) {
            firstBread.addOffset(flags);
        }
    }

    @Override
    public void reload() {
        super.reload();
        offsetToFlag = getConfig().getDoubleElse(getConfigName() + ".threshold", 0.001);
        this.maxAdvantage = getConfig().getDoubleElse(getConfigName() + ".max-advantage", 1);
        this.immediate = getConfig().getDoubleElse(getConfigName() + ".immediate-setback-threshold", 0.01);
        this.multiplier = getConfig().getDoubleElse(getConfigName() + ".setback-decay-multiplier", 0.98);
        this.ceiling = getConfig().getDoubleElse(getConfigName() + ".max-ceiling", 2);
    }

    private void handleAllDoneWithVels() {
        this.secondBread = null;
        overriddenVels.clear();

        // We don't need to flag this anymore
        if (lastSent != null && player.lastTransactionReceived.get() > lastSent.getTransaction()) {
            lastSent = null;
        }
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        this.wasExempt = false;
        if (!MovementProfiles.forPlayer(player).usesPacketVelocityModifiers(player)) {
            handleAllDoneWithVels();
            return;
        }
        if (predictionComplete.isTeleport()) {
            handleTeleport(predictionComplete.getPredictionResult().getSetBackData());
            handleAllDoneWithVels();
            return;
        }

        final boolean possibleSkip = this.canTickSkip;
        // If there is a velocity going to 0, and the player moved slow enough to reach 0, assume movement skipped.
        if (this.canTickSkip) {
            this.canTickSkip = false;
            if (this.firstBread != null) this.firstBread.addOffset(0);
            if (this.secondBread != null) this.secondBread.addOffset(0);
        }

        if (predictionComplete.getPredictionResult().getInitialStartingVel().hasPacketModifier(this.firstBread)) {
            List<PredictionResult> realities = predictionComplete.getPredictionResult().getRealities();
            boolean hasValidNonBreadVector = false;

            for (PredictionResult vector : realities) {
                if (!vector.getInitialStartingVel().hasPacketModifier(this.firstBread) && vector.getFlagSeverity() == 0) {
                    hasValidNonBreadVector = true;
                    break;
                }
            }
            // Prevent abuse by replaying the first bread over and over
            if (!hasValidNonBreadVector) {
                this.firstBread = null;
            }
        }

        boolean usedSecondBread = predictionComplete.getPredictionResult().getInitialStartingVel().hasPacketModifier(this.secondBread);

        if (this.secondBread != null && this.secondBread.getOffset() > offsetToFlag
                && !player.compensatedEntities.getSelf().isDead && player.gamemode != GameMode.SPECTATOR) {
            // TODO: Perhaps if the velocity overrode other velocities, we flag multiple times?
            // TODO: Perhaps add maximum latency for velocity.
            if (player.getSetbackTeleportUtil().isDebug()) { LogUtil.info("Velocity flag: " + this.secondBread.getOffset() + " skip=" + possibleSkip); }
            if (!this.secondBread.isSetbackVel()) {
                final double offset = this.secondBread.getOffset();
                this.threshold = Math.min(this.threshold + offset, ceiling);
                String offsetDescription = offset == Double.MAX_VALUE
                        ? "ignored"
                        : NumFormatter.formatNumberStandard(offset);
                flag("offset=" + offsetDescription + " threshold=" + NumFormatter.formatNumberStandard(threshold));
                //
                if (offset >= immediate || threshold >= maxAdvantage) { setbackIfAboveSetbackVL(); }

            } else { // We still need to increase violations.
                player.getSetbackTeleportUtil().executeViolationSetback();
            }
        }

        if (this.secondBread != null && offsetToFlag > this.secondBread.getOffset()) {
            threshold *= multiplier;
        }

        handleAllDoneWithVels();
    }

    // TODO: OOP
    public void exempt() {
        handleTeleport(null);
    }

    @Getter private boolean wasExempt = false;

    private void markVelocityOverriddenByTeleport() {
        // The teleport overrode this velocity completely, don't bother with it
        this.secondBread = null; this.wasExempt = true;
    }

    public void handleTeleport(TeleportData data) {
        if (data == null) {
            markVelocityOverriddenByTeleport();
        } else if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_5)) {
            if (!hasRelativeDelta(data)) {
                markVelocityOverriddenByTeleport();
                return;
            }
            // MCP-Reborn ClientPacketListener#setValuesFromPositionPacket
            // applies PositionMoveRotation#calculateAbsolute to the current
            // delta movement. Relative delta flags preserve packet-applied
            // client velocity through the teleport, one axis at a time.
            transformPendingPacketVelocities(data, true);
        } else if (data.isAbsolute()) {
            markVelocityOverriddenByTeleport();
        } else {
            // Relative teleport only preserved some of it (or none of it)
            transformPendingPacketVelocities(data, false);
        }
    }

    private boolean hasRelativeDelta(TeleportData data) {
        return data.isRelativeDeltaX() || data.isRelativeDeltaY() || data.isRelativeDeltaZ();
    }

    private void transformPendingPacketVelocities(TeleportData data, boolean useDeltaFlags) {
        if (shouldTransformPacketVelocity(data, this.secondBread)) {
            this.secondBread.setVel(transformPendingPacketVelocity(data, this.secondBread.getVel(), useDeltaFlags));
        }
        if (shouldTransformPacketVelocity(data, this.firstBread)) {
            this.firstBread.setVel(transformPendingPacketVelocity(data, this.firstBread.getVel(), useDeltaFlags));
        }
        for (TransactionVel overriddenVel : this.overriddenVels) {
            if (shouldTransformPacketVelocity(data, overriddenVel)) {
                overriddenVel.setVel(transformPendingPacketVelocity(data, overriddenVel.getVel(), useDeltaFlags));
            }
        }
    }

    private boolean shouldTransformPacketVelocity(TeleportData data, TransactionVel velocity) {
        return velocity != null && velocity.getTransaction() <= data.getTransaction();
    }

    private Vec3 transformPendingPacketVelocity(TeleportData data, Vec3 velocity, boolean useDeltaFlags) {
        return useDeltaFlags ? data.transformInheritedVelocity(velocity) : data.modifyVector(velocity);
    }

    protected boolean shouldUseBundledProof() {
        return MovementProfiles.forPlayer(player).shouldUseBundledPacketProof(player);
    }

    protected CultPlayer.TrackedTransaction bundlePacketWithTrailingTransaction(PacketSendEvent event, Packet<? super ClientGamePacketListener> packet) {
        CultPlayer.TrackedTransaction transaction = player.createTrackedTransactionPacketForBundle();
        if (transaction == null) {
            return null;
        }

        // MCP-Reborn processes ClientboundBundlePacket children in order on the
        // packet processor; the trailing ping proves every earlier child in this
        // bundle, including the velocity/explosion packet, has been handled before
        // any later LocalPlayer#tick movement can consume it.
        event.setNmsPacket(new ClientboundBundlePacket(List.of(packet, transaction.packet())));
        event.getTasksAfterSend().add(() -> player.markTrackedTransactionPacketSent(transaction));
        return transaction;
    }

    protected int handleEvent(Vec3 playerVelocity, boolean isVel, PacketSendEvent event, int sourceEntityId) {
        // Wrap velocity between two transactions, matching Cult3.0-clean's
        // uncertainty model for clients where the packet is not bundle-proven.
        int transaction = registerTransactionSandwich(playerVelocity, isVel, sourceEntityId);
        event.getTasksAfterSend().add(player::sendTransaction);
        return transaction;
    }

    public int handleDebugPseudoEvent(Vec3 playerVelocity, boolean isVel, int sourceEntityId) {
        player.sendTransaction();
        int transaction = registerTransactionSandwich(playerVelocity, isVel, sourceEntityId);
        player.sendTransaction();
        return transaction;
    }

    public int handleDebugDirectPacketEvent(
            Vec3 playerVelocity,
            boolean isVel,
            int sourceEntityId,
            Packet<? super ClientGamePacketListener> packet
    ) {
        CultPlayer.TrackedTransaction transaction = shouldUseBundledProof()
                ? player.createTrackedTransactionPacketForBundle()
                : null;
        if (transaction != null) {
            player.user.sendPacket(new ClientboundBundlePacket(List.of(packet, transaction.packet())));
            player.markTrackedTransactionPacketSent(transaction);
            handleEventAfterTransaction(playerVelocity, isVel, transaction.transaction(), sourceEntityId);
            return transaction.transaction();
        }

        CultPlayer.TrackedTransaction firstProof = sendDirectTrackedTransaction();
        if (firstProof == null) {
            player.user.sendPacket(packet);
            return -1;
        }
        int sandwichTransaction = registerTransactionSandwich(playerVelocity, isVel, sourceEntityId);
        player.user.sendPacket(packet);
        sendDirectTrackedTransaction();
        return sandwichTransaction;
    }

    private CultPlayer.TrackedTransaction sendDirectTrackedTransaction() {
        CultPlayer.TrackedTransaction transaction = player.createTrackedTransactionPacketForDeferredSend();
        if (transaction == null) {
            return null;
        }

        player.user.sendPacket(transaction.packet());
        player.markTrackedTransactionPacketSent(transaction);
        return transaction;
    }

    protected int registerTransactionSandwich(Vec3 playerVelocity, boolean isVel, int sourceEntityId) {
        int lastTransactionSent = player.lastTransactionSent.get();
        lastSent = new TransactionVel(playerVelocity, lastTransactionSent, isVel, isSetbackVal, sourceEntityId);
        boolean thisTransIsSetback = this.isSetbackVal;

        final Runnable applyFirstBread = () -> {
            TransactionVel transactionVel = new TransactionVel(playerVelocity, lastTransactionSent, isVel, thisTransIsSetback, sourceEntityId);

            if (!isVel && shouldCarryPreviousPacketModifier(secondBread, false, lastTransactionSent)) {
                transactionVel.setVel(transactionVel.getVel().add(secondBread.getVel()));
            }
            firstBread = transactionVel;
        };
        player.latencyUtils.addRealTimeTaskNow(applyFirstBread);
        player.latencyUtils.addRealTimeTask(lastTransactionSent + 1, () -> {
            TransactionVel transactionVel = new TransactionVel(playerVelocity, lastTransactionSent, isVel, thisTransIsSetback, sourceEntityId);

            // The first bread was overridden to stop abuse
            if (this.firstBread == null) return;

            if (secondBread != null && isVel && shouldCarryPreviousPacketModifier(secondBread, true, lastTransactionSent)) {
                overriddenVels.add(secondBread);
            }

            transactionVel.setOffset(firstBread.getOffset());
            if (!isVel && shouldCarryPreviousPacketModifier(secondBread, false, lastTransactionSent)) {
                transactionVel.setVel(transactionVel.getVel().add(secondBread.getVel()));
            }
            firstBread = null;
            secondBread = transactionVel;
        });

        return lastTransactionSent + 1;
    }

    protected void registerExternallyAcknowledgedEvent(Vec3 playerVelocity, boolean isVel, int sourceEntityId) {
        int transaction = player.lastTransactionSent.get();
        TransactionVel transactionVel = new TransactionVel(
                playerVelocity, transaction, isVel, isSetbackVal, sourceEntityId);
        lastSent = transactionVel;

        if (!isVel && shouldCarryPreviousPacketModifier(secondBread, false, transaction)) {
            transactionVel.setVel(transactionVel.getVel().add(secondBread.getVel()));
        }
        firstBread = transactionVel;
    }

    protected void acknowledgeExternallyAcknowledgedEvent() {
        TransactionVel transactionVel = firstBread;
        if (transactionVel == null) {
            return;
        }
        if (secondBread != null && transactionVel.isVelocity()
                && shouldCarryPreviousPacketModifier(secondBread, true, transactionVel.getTransaction())) {
            overriddenVels.add(secondBread);
        }
        firstBread = null;
        secondBread = transactionVel;
    }

    protected void handleEventAfterTransaction(Vec3 playerVelocity, boolean isVel, int transaction, int sourceEntityId) {
        if (transaction <= 0) return;

        lastSent = new TransactionVel(playerVelocity, transaction, isVel, isSetbackVal, sourceEntityId);
        boolean thisTransIsSetback = this.isSetbackVal;

        player.latencyUtils.addRealTimeTask(transaction, () -> {
            TransactionVel transactionVel = new TransactionVel(playerVelocity, transaction, isVel, thisTransIsSetback, sourceEntityId);

            if (secondBread != null && isVel && shouldCarryPreviousPacketModifier(secondBread, true, transaction)) {
                overriddenVels.add(secondBread);
            }
            if (!isVel && shouldCarryPreviousPacketModifier(secondBread, false, transaction)) {
                transactionVel.setVel(transactionVel.getVel().add(secondBread.getVel()));
            }

            firstBread = null;
            secondBread = transactionVel;
        });
    }

    private boolean shouldCarryPreviousPacketModifier(TransactionVel previous, boolean currentIsVelocity, int currentTransaction) {
        if (previous == null) {
            return false;
        }

        // MCP-Reborn applies velocity/explosion packets immediately to delta movement,
        // then LocalPlayer#tick consumes that delta before Minecraft#tick sends
        // ServerboundClientTickEndPacket. Once Cult has seen that tick-end after the
        // packet's proof transaction, the previous modifier is already consumed.
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_5)
                && player.packetStateData.lastClientTickEndTransaction >= previous.getTransaction()) {
            return false;
        }

        // ClientboundSetEntityMotionPacket calls setDeltaMovement, so a velocity
        // packet after an explosion replaces the earlier explosion delta. Do not
        // fold that earlier explosion into the next explosion; MovementModifiers
        // will apply the newer velocity as the replacement state.
        return currentIsVelocity || !hasVelocityOverrideBetween(previous, currentTransaction);
    }

    private boolean hasVelocityOverrideBetween(TransactionVel previous, int currentTransaction) {
        KnockbackHandler knockbackHandler = player.checkManager.getKnockbackHandler();
        return isVelocityOverrideBetween(previous, knockbackHandler.firstBread, currentTransaction)
                || isVelocityOverrideBetween(previous, knockbackHandler.secondBread, currentTransaction);
    }

    private boolean isVelocityOverrideBetween(TransactionVel previous, TransactionVel velocity, int currentTransaction) {
        return velocity != null
                && velocity.isVelocity()
                && velocity.getTransaction() > previous.getTransaction()
                && velocity.getTransaction() <= currentTransaction;
    }

    public boolean hasPacketVelocity() {
        return firstBread != null || secondBread != null;
    }

    public void clearVelocitiesFromSourceEntity(int entityId) {
        if (firstBread != null && firstBread.getSourceEntityId() == entityId) {
            firstBread = null;
        }
        if (secondBread != null && secondBread.getSourceEntityId() == entityId) {
            secondBread = null;
        }
        if (lastSent != null && lastSent.getSourceEntityId() == entityId) {
            lastSent = null;
        }
        overriddenVels.removeIf(vel -> vel.getSourceEntityId() == entityId);
    }

    public void keepOnlyVelocitiesFromSourceEntity(int entityId) {
        if (firstBread != null && firstBread.getSourceEntityId() != entityId) {
            firstBread = null;
        }
        if (secondBread != null && secondBread.getSourceEntityId() != entityId) {
            secondBread = null;
        }
        if (lastSent != null && lastSent.getSourceEntityId() != entityId) {
            lastSent = null;
        }
        overriddenVels.removeIf(vel -> vel.getSourceEntityId() != entityId);
    }

}
