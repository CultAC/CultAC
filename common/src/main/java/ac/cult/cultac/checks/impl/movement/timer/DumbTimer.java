package ac.cult.cultac.checks.impl.movement.timer;

import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.type.PositionListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.utils.anticheat.update.PositionUpdate;
import ac.cult.cultac.utils.data.TeleportAcceptData;

/**
 * Caps the client's tick backlog alongside the precise TimerA check. Each client tick counts once. A Java tick counts at
 * its movement: a MovePlayer, which while riding is the tick's passenger Rot, or a vehicle move without one. A Java tick
 * that sent neither counts at its ServerboundClientTickEnd. A Bedrock tick is its PlayerAuthInputPacket; Geyser's Java
 * movement and tick-end packets only project it, so they never count.
 */
public class DumbTimer extends CultProcessor implements PositionListener {
    private static final long DEFAULT_BACKLOG_LIMIT = 1_000_000_000L;

    public DumbTimer(CultPlayer cultPlayer) {
        super(cultPlayer);
    }

    double balance = System.nanoTime() - DEFAULT_BACKLOG_LIMIT;
    double lastLongTeleport = System.nanoTime();
    long limitAbuseOverPing = DEFAULT_BACKLOG_LIMIT;
    // Whether the current Java client tick has counted.
    private boolean countedThisClientTick;

    private boolean isServerTickingNormally() {
        return Math.abs(player.packetStateData.serverTickRate - 20.0F) < 1.0E-3F
                && (!player.packetStateData.serverTicksFrozen
                        || player.packetStateData.serverFrozenTickStepsRemaining > 0);
    }

    public void resetTimerWindow() {
        this.balance = System.nanoTime() - (limitAbuseOverPing == -1 ? DEFAULT_BACKLOG_LIMIT : limitAbuseOverPing);
        this.lastLongTeleport = System.nanoTime();
    }

    @Override
    public void onPositionUpdate(final PositionUpdate positionUpdate) {
        final TeleportAcceptData teleportData = positionUpdate.getTeleportData();
        if (teleportData.isTeleport()) {
            final double teleportDistance = positionUpdate.getFrom().distanceTo(positionUpdate.getTo());
            final boolean spawnTeleport = teleportData.isInitialSpawnTeleport();

            // Preserve the existing two-second spawn/long-teleport allowance.
            if (spawnTeleport || teleportDistance > 32) {
                this.lastLongTeleport = System.nanoTime();
                this.balance = System.nanoTime() - 2000e6;
                final String teleportNote = "long teleport";
                debug(() -> teleportNote);
            }
        }
    }

    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        handleMovement(event);
    }

    /**
     * LocalPlayer#tick sends a ridden tick's Rot before its MoveVehicle, and that Rot already counted the tick. A
     * client-tick vehicle move accepted without one, during the vehicle-switch buffer, counts the tick itself.
     */
    @CultPacketHandler
    public void onMoveVehicle(
            PacketReceiveEvent<ServerboundMoveVehicle> event, CultPlayer player, ServerboundMoveVehicle packet) {
        if (countedThisClientTick || !player.packetStateData.isVehicleMovementFromClientTick()) return;
        handleMovement(event);
    }

    /** Minecraft#tick ends every client tick with this packet, so a tick that sent no movement counts here. */
    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        if (player.isBedrockMovement() || event.isCancelled()) return;
        if (!countedThisClientTick && exceedsBacklog()) rejectTick(event);
        // A rejected tick end does not end the tick.
        if (!event.isCancelled()) countedThisClientTick = false;
    }

    /** Counts one Bedrock client tick. Returns whether its frame is rejected. */
    public boolean onBedrockAuthInput() {
        if (!exceedsBacklog()) return false;
        player.getSetbackTeleportUtil().executeNonSimulatingSetback();
        return !player.isDisabled()
                && player.shouldEnforceMovementSetbacks()
                && player.getSetbackTeleportUtil().shouldBlockMovement();
    }

    private void handleMovement(final PacketReceiveEvent event) {
        if (player.isBedrockMovement() || player.packetStateData.lastPacketWasTeleport) return;
        countedThisClientTick = true;
        if (exceedsBacklog()) rejectTick(event);
    }

    /** Counts one client tick. Returns whether it put the client ahead of real time, outside an exemption. */
    private boolean exceedsBacklog() {
        if (!isServerTickingNormally()) {
            return false;
        }

        final long curTime = System.nanoTime();

        // Don't let the player fall behind real time by more than the configured limit.
        // However, if the player long teleported within the last two seconds, allow it
        //
        // Be strict if the player used an item recently
        // TODO: the above comment was never implemented? this really is a dumb timer check.
        if (limitAbuseOverPing != -1 && curTime - this.lastLongTeleport > 2000e6) {
            this.balance = Math.max(this.balance, curTime - limitAbuseOverPing);
        }

        this.balance += 45e6;

        if (this.balance <= curTime) {
            return false;
        }
        final double ahead = this.balance;
        // Try to avoid excessive setbacks while maintaining security.
        this.balance -= 45e6;
        if (player.checkManager.getSimulationProcessor().isExempt()) {
            return false;
        }
        debug(() -> "balance=" + ahead + " cur=" + curTime);
        return true;
    }

    private void rejectTick(final PacketReceiveEvent event) {
        player.getSetbackTeleportUtil().executeNonSimulatingSetback();
        if (!player.isDisabled()
                && player.shouldEnforceMovementSetbacks()
                && player.getSetbackTeleportUtil().shouldBlockMovement()) {
            event.setCancelled(true);
        }
    }

    @Override
    public void reload() {
        // Keep the former TimerLimit setting so existing server configurations still apply.
        limitAbuseOverPing = getConfig().getLongElse("TimerLimit.ping-abuse-limit-threshold", 1000L);
        if (limitAbuseOverPing != -1) {
            limitAbuseOverPing *= 1_000_000L;
        }
    }
}
