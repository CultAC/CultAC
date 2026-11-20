package ac.cult.cultac.events.packets;

import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.impl.movement.timer.DumbTimer;
import ac.cult.cultac.checks.impl.movement.timer.TimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.VehicleTimer;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundTickingState;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundTickingStep;

public class PacketServerTickingState extends CultProcessor implements CheckListener {
    public PacketServerTickingState(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onTickingState(
            PacketSendEvent<ClientboundTickingState> event, CultPlayer player, ClientboundTickingState packet) {
        player.itemUseBurst.tickRateSent(packet.tickRate());
        deferToClient(event, () -> {
            boolean wasTickingNormally = isTickingNormally(
                    player.packetStateData.serverTickRate,
                    player.packetStateData.serverTicksFrozen,
                    player.packetStateData.serverFrozenTickStepsRemaining);
            player.packetStateData.serverTickRate = Math.max(packet.tickRate(), 1F);
            player.packetStateData.serverTicksFrozen = packet.frozen();
            if (!packet.frozen()) {
                player.packetStateData.serverFrozenTickStepsRemaining = 0;
            }

            if (wasTickingNormally
                    != isTickingNormally(
                            player.packetStateData.serverTickRate,
                            player.packetStateData.serverTicksFrozen,
                            player.packetStateData.serverFrozenTickStepsRemaining)) {
                resetTimerWindows();
            }
        });
    }

    @CultPacketHandler
    public void onTickingStep(
            PacketSendEvent<ClientboundTickingStep> event, CultPlayer player, ClientboundTickingStep packet) {
        deferToClient(event, () -> {
            boolean wasTickingNormally = isTickingNormally(
                    player.packetStateData.serverTickRate,
                    player.packetStateData.serverTicksFrozen,
                    player.packetStateData.serverFrozenTickStepsRemaining);
            player.packetStateData.serverFrozenTickStepsRemaining = packet.tickSteps();

            if (wasTickingNormally
                    != isTickingNormally(
                            player.packetStateData.serverTickRate,
                            player.packetStateData.serverTicksFrozen,
                            player.packetStateData.serverFrozenTickStepsRemaining)) {
                resetTimerWindows();
            }
        });
    }

    private void resetTimerWindows() {
        player.checkManager.getCheck(TimerCheck.class).resetTimerWindow();
        player.checkManager.getCheck(VehicleTimer.class).resetTimerWindow();
        player.checkManager.getListener(DumbTimer.class).resetTimerWindow();
    }

    private void deferToClient(PacketSendEvent<?> event, Runnable update) {
        if (player.usesModernJavaItemUse()) {
            var proof = player.appendTrailingProofTransaction(event);
            if (proof != null) {
                if (!event.isInsideBundle()) event.bundle();
                player.latencyUtils.addRealTimeTask(proof.transaction(), update);
                return;
            }
        }
        player.latencyUtils.addRealTimeTaskNow(update);
    }

    private boolean isTickingNormally(float tickRate, boolean frozen, int frozenTickStepsRemaining) {
        return Math.abs(tickRate - 20.0F) < 1.0E-3F && (!frozen || frozenTickStepsRemaining > 0);
    }
}
