package ac.grim.grimac.checks.impl.movement.timer;

import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.checks.type.PositionListener;
import ac.grim.grimac.network.GrimPacketGroup;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.PacketGroup;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PositionUpdate;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

// TODO: Put behind real timer, register check, test check
public class DumbTimer extends GrimProcessor implements PositionListener {
    private static final double TIMER_HEADROOM = 750e6;

    public DumbTimer(GrimPlayer grimPlayer) { super(grimPlayer); }

    double balance = System.nanoTime() - TIMER_HEADROOM;
    double lastLongTeleport = System.nanoTime();
    double scalar = 1;

    private boolean isServerTickingNormally() {
        return Math.abs(player.packetStateData.serverTickRate - 20.0F) < 1.0E-3F
                && (!player.packetStateData.serverTicksFrozen
                || player.packetStateData.serverFrozenTickStepsRemaining > 0);
    }

    public void resetTimerWindow() {
        this.balance = System.nanoTime() - TIMER_HEADROOM;
        this.lastLongTeleport = System.nanoTime();
    }

    @Override
    public void onPositionUpdate(final PositionUpdate positionUpdate) {
        final ac.grim.grimac.utils.data.TeleportAcceptData teleportData = positionUpdate.getTeleportData();
        if (teleportData.isTeleport()) {
            final double teleportDistance = positionUpdate.getFrom().distanceTo(positionUpdate.getTo());
            final boolean spawnTeleport = teleportData.isInitialSpawnTeleport();

            // When teleport to a new chunk, allow 20 extra ticks to account for respawning and such
            if (spawnTeleport || teleportDistance > 32) {
                this.lastLongTeleport = System.nanoTime();
                this.balance = System.nanoTime() - 2000e6;
                final String teleportNote = "long teleport";
                debug(() -> teleportNote);
            }
        }
    }

    private void handleMovePlayer(final PacketReceiveEvent event, ServerboundMovePlayerPacket packet) {
        if (!isServerTickingNormally()) {
            return;
        }

        if (!player.packetStateData.lastPacketWasTeleport) {
            final long curTime = System.nanoTime();

            // Be strict if the player used an item recently
            final double strictness = TIMER_HEADROOM * this.scalar;

            // Don't let the player fall behind real time by more than strictness
            // However, if the player long teleported within the last two seconds, allow it
            if (System.nanoTime() - this.lastLongTeleport > 2000e6) {
                this.balance = Math.max(this.balance, curTime - strictness);
            }

            this.balance += 45e6;

            if (this.balance > curTime) {
                if (!player.checkManager.getSimulationProcessor().isExempt()) {
                    debug(() -> "balance=" + this.balance + " cur=" + curTime);
                    player.getSetbackTeleportUtil().executeNonSimulatingSetback();
                }
                // Try to avoid excessive setbacks while maintaining security.
                this.balance -= 45e6;
            }
        }
    }

    @GrimPacketHandler
    @GrimPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, GrimPlayer player, ServerboundMovePlayerPacket packet) {
        handleMovePlayer(event, packet);
    }

    @Override
    public void reload() { this.scalar = getConfig().getDoubleElse("blink-ping-threshold", 1); }
}
