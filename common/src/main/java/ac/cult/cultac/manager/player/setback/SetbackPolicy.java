package ac.cult.cultac.manager.player.setback;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.runner.KnockbackHandler;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.data.SetbackPosWithVector;
import ac.cult.cultac.utils.lists.EvictingQueue;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * When a setback is sent.
 *
 * <ul>
 *   <li>Checks ask for one; it is refused while a Cult correction is pending, before the player is placed, in
 *       observe-only mode, and for exempt players. A flag while a server teleport is pending makes that teleport
 *       block movement like a Cult correction.
 *   <li>A player that stops moving for longer than the freeze time is set back once it fails to stay in place, but
 *       never one who is dead or not yet placed.
 *   <li>A setback in an unloaded chunk ticks nothing and resyncs fully.
 *   <li>A correction the client ignored is sent again; observe-only lets go of it and of the held stand-in move.
 * </ul>
 */
public final class SetbackPolicy {
    private final CultPlayer player;
    private final SafePositionTracker safePositions;
    private final CorrectionState corrections;
    private final MovementGate gate;
    private final CorrectionBuilder builder;
    private final CorrectionSender sender;
    private final BedrockTeleportTracker bedrock;
    private final VehicleTeleportTracker vehicles;
    private final BooleanSupplier debug;
    private int freeze = 5000;

    public SetbackPolicy(
            CultPlayer player,
            SafePositionTracker safePositions,
            CorrectionState corrections,
            MovementGate gate,
            CorrectionBuilder builder,
            CorrectionSender sender,
            BedrockTeleportTracker bedrock,
            VehicleTeleportTracker vehicles,
            BooleanSupplier debug) {
        this.player = player;
        this.safePositions = safePositions;
        this.corrections = corrections;
        this.gate = gate;
        this.builder = builder;
        this.sender = sender;
        this.bedrock = bedrock;
        this.vehicles = vehicles;
        this.debug = debug;
    }

    public void executeForceResync(String cause) {
        if (CultAPI.INSTANCE.getConfigManager().isObserveOnly()) return;
        if (!player.shouldEnforceMovementSetbacks()) return;
        if (player.isDisabled() || player.gamemode == GameMode.SPECTATOR)
            return; // We don't care about spectators, they don't flag
        if (safePositions.current() == null) return; // Player hasn't spawned yet
        if (corrections.isPending()) return; // Don't spam setbacks // TODO: Remove plugin teleport status
        if (debug.getAsBoolean()) {
            LogUtil.warn("Setback sent to " + player.getName() + ", resync: " + cause);
        }
        blockMovementsUntilResync(
                true, // simulate next tick
                true, // full resync
                false, // ignore if no forced change
                null);
    }

    public void executeNonSimulatingSetback() {
        if (CultAPI.INSTANCE.getConfigManager().isObserveOnly()) return;
        if (!player.shouldEnforceMovementSetbacks()) return;
        if (player.isDisabled() || player.gamemode == GameMode.SPECTATOR)
            return; // We don't care about spectators, they don't flag
        if (corrections.isPending()) return; // Don't spam setbacks // TODO: Remove plugin teleport status
        if (safePositions.current() == null) return; // Player hasn't spawned yet
        if (debug.getAsBoolean()) {
            LogUtil.warn("Setback sent to " + player.getName() + ", non simulating");
        }
        blockMovementsUntilResync(
                false, // timer and other non-simulating setbacks must not advance a tick
                false, // full resync
                false, // ignore if no forced change
                null);
    }

    public boolean executeViolationSetback() {
        return executeViolationSetback(null);
    }

    /** A setback for the rejected {@code flagged} movement; a ridden vehicle takes the tick simulated for it. */
    public boolean executeViolationSetback(PredictionResult flagged) {
        if (isExempt()) return false;
        if (corrections.isPending()) { // Don't spam setbacks // TODO: Remove plugin teleport status
            return false;
        }
        if (debug.getAsBoolean()) {
            LogUtil.warn("Setback sent to " + player.getName() + ", violation");
        }
        blockMovementsUntilResync(
                true, // simulate next tick
                false, // full resync
                false, // ignore if no forced change
                flagged);
        return true;
    }

    public boolean executeTooHighLatencySetback(String cause) {
        if (isExempt()) return false;
        // Latency is measured by forwarded movement: a dead client's movement is never forwarded, and a client
        // that has not accepted its placement teleport has no movement to send yet.
        if (player.compensatedEntities.getSelf().isDead || !gate.hasFullyLoaded()) return false;
        if (debug.getAsBoolean()) {
            LogUtil.warn("Setback sent to " + player.getName() + ", high latency, " + cause);
        }
        blockMovementsUntilResync(
                true, // simulate next tick
                false, // full resync
                true, // ignore if no forced change
                null);
        return true;
    }

    private boolean isExempt() {
        if (CultAPI.INSTANCE.getConfigManager().isObserveOnly()) return true;
        // Not exempting spectators here because timer check for spectators is actually valid.
        // Player hasn't spawned yet
        if (safePositions.current() == null) return true;
        // Setbacks aren't allowed
        if (player.isDisabled()) {
            return true;
        }
        if (!player.shouldEnforceMovementSetbacks()) return true;
        // Player has permission to cheat, permission not given to OP by default.
        return player.noSetbackPermission && player.platformPlayer != null;
    }

    private void maybeThrowSetbackDebug(boolean simulateNext, boolean fullResync, boolean ignoreUnchanged) {
        if (!player.throwError) return;
        try {
            final String setbackDebug = "Setback: " + simulateNext + ", " + fullResync + ", " + ignoreUnchanged;
            throw new RuntimeException(setbackDebug);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void blockMovementsUntilResync(
            boolean simulateNext, boolean fullResync, boolean ignoreUnchanged, PredictionResult flagged) {
        if (safePositions.current() == null) return; // Hasn't spawned
        // A teleport changes neither attributes nor the sprint flag; the rejected moves' modifier evidence is dropped.
        if (!player.isBedrockMovement())
            player.sprintState.restore(safePositions.current().getSprintOutcomes());

        // If the chunk hasn't loaded, we can't simulate their movement yet
        if (gate.insideUnloadedChunk()) {
            simulateNext = false;
            fullResync = true;
        }

        SetBackData required = corrections.required();
        if (required != null) {
            required.setPlugin(false); // The player has illegal movement, block from vanilla ac override
        }
        maybeThrowSetbackDebug(simulateNext, fullResync, ignoreUnchanged);
        sender.resyncWorld();

        SetBackData data = builder.build(
                new CorrectionBuilder.Request(simulateNext, ignoreUnchanged, flagged),
                new CorrectionBuilder.Inputs(
                        safePositions.current(),
                        bedrock.paperVisiblePosition(),
                        SetbackEntity.vehicleOf(player),
                        corrections.correctionTransaction(),
                        safePositions.rejectsLatestMovement(),
                        gate.hasFullyLoaded(),
                        // The Java knockback and explosion handlers track the player's own impulses, and
                        // Entity#rideTick zeroes a passenger's delta movement: a vehicle takes none of them.
                        player.isBedrockMovement()
                                || SetbackEntity.current(player).isPlayer()));
        if (data == null) return;

        if (debug.getAsBoolean()) {
            LogUtil.info("Teleported " + player.getName() + " to "
                    + data.getTeleportData().getLocation()
                    + " with vel " + data.getVelocity() + " using " + simulateNext + " " + fullResync + " "
                    + ignoreUnchanged);
        }
        sender.sendSetback(data, simulateNext);
        // Something weird has occurred in the player's movement, block offsets until the client accepts this
        // setback. Only its acceptance clears the block, so a setback that was never tracked must not raise it.
        if (fullResync && corrections.required() == data) {
            corrections.blockOffsets();
        }
        if (debug.getAsBoolean()) {
            LogUtil.info(
                    "Teleport sent | resync=" + fullResync + ", infc=" + ignoreUnchanged + ", simNext=" + simulateNext);
        }
    }

    public void resendIgnoredRequiredSetback(String reason) {
        SetBackData required = corrections.required();
        if (required == null || required.isPlugin()) {
            return;
        }
        if (CultAPI.INSTANCE.getConfigManager().isObserveOnly()) {
            releaseRequiredSetback(new Vec3(player.x, player.y, player.z));
            return;
        }

        required.setPlugin(false);
        if (debug.getAsBoolean()) {
            LogUtil.info("Reissuing ignored Cult setback for " + player.getName() + ": " + reason);
        }
        sender.sendSetback(required);
    }

    /** Lets go of a setback and held move that observe-only would otherwise keep waiting on. */
    public void observeOnlyStarted() {
        sender.dropPendingServerMove();
        SetBackData required = corrections.required();
        if (required != null && !required.isComplete()) {
            releaseRequiredSetback(new Vec3(player.x, player.y, player.z));
        }
        vehicles.clearBedrockVehicleSetback();
        corrections.unblockOffsets();
    }

    public void releaseRequiredSetback(Vec3 position) {
        corrections.complete(corrections.required());
        if (!player.inVehicle()) {
            safePositions.write(
                    new SetbackPosWithVector(position, Vec3.ZERO), SafePositionTracker.Write.OBSERVE_RELEASE);
        }
    }

    // Every 60 ms we check if a player has taken knockback
    // We do 60 ms instead of 50 ms to try to mitigate any advantages gained by abusing this
    //
    // We will assume that velocity is more sensitive to not moving than general movement
    public void checkIfMustMove() {
        checkIfMustMove(System.nanoTime());
    }

    public void checkIfMustMove(long currentTime) {
        if (!mustMoveDue(currentTime)) return;
        final long elapsed = currentTime - lastMovement();
        // if the player must move
        if (player.checkManager
                .getSimulationProcessor()
                .tryToAchievePointThree(new Vec3(player.x, player.y, player.z), player.xRot, player.yRot)
                .getFlags()
                .isEmpty()) {
            return;
        }
        // This will activate the velocity part of this check
        final String latencyReason = "move, " + TimeUnit.NANOSECONDS.toMillis(elapsed) + "ms";
        executeTooHighLatencySetback(latencyReason);
    }

    /** True when {@link #checkIfMustMove} at {@code currentTime} goes on to test the player's position. */
    public boolean mustMoveDue(long currentTime) {
        final EvictingQueue<Long> movementTimes =
                player.checkManager.getSimulationProcessor().getLastMovementTime();
        if (movementTimes.isEmpty()) return false;
        if (player.inVehicle()) return false;
        final KnockbackHandler knockback = player.checkManager.getKnockbackHandler();
        if (knockback.lastSent != null) return false;

        // If the player hasn't sent a movement in 1.5 seconds, setback (and send velocity to activate other part)
        final long elapsed = currentTime - lastMovement();
        final long freezeNanos = TimeUnit.NANOSECONDS.convert(freeze, TimeUnit.MILLISECONDS);
        return elapsed > freezeNanos;
    }

    private long lastMovement() {
        return Collections.max(player.checkManager.getSimulationProcessor().getLastMovementTime());
    }

    public void setFreeze(int freezeMillis) {
        this.freeze = freezeMillis;
    }
}
