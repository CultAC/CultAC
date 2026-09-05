package ac.cult.cultac.events.packets;

import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.impl.movement.timer.DumbTimer;
import ac.cult.cultac.checks.impl.movement.timer.TimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.VehicleTimer;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.game.ClientboundTickingStepPacket;
import net.minecraft.network.protocol.game.ClientboundTickingStatePacket;

public class PacketServerTickingState extends CultProcessor implements CheckListener {
    public PacketServerTickingState(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onTickingState(PacketSendEvent event, CultPlayer player, ClientboundTickingStatePacket packet) {
        player.latencyUtils.addRealTimeTaskNow(() -> {
            boolean wasTickingNormally = isTickingNormally(
                    player.packetStateData.serverTickRate,
                    player.packetStateData.serverTicksFrozen,
                    player.packetStateData.serverFrozenTickStepsRemaining
            );
            player.packetStateData.serverTickRate = packet.tickRate();
            player.packetStateData.serverTicksFrozen = packet.isFrozen();
            if (!packet.isFrozen()) {
                player.packetStateData.serverFrozenTickStepsRemaining = 0;
            }

            if (wasTickingNormally != isTickingNormally(
                    player.packetStateData.serverTickRate,
                    player.packetStateData.serverTicksFrozen,
                    player.packetStateData.serverFrozenTickStepsRemaining
            )) {
                resetTimerWindows();
            }
        });
    }

    @CultPacketHandler
    public void onTickingStep(PacketSendEvent event, CultPlayer player, ClientboundTickingStepPacket packet) {
        player.latencyUtils.addRealTimeTaskNow(() -> {
            boolean wasTickingNormally = isTickingNormally(
                    player.packetStateData.serverTickRate,
                    player.packetStateData.serverTicksFrozen,
                    player.packetStateData.serverFrozenTickStepsRemaining
            );
            player.packetStateData.serverFrozenTickStepsRemaining = packet.tickSteps();

            if (wasTickingNormally != isTickingNormally(
                    player.packetStateData.serverTickRate,
                    player.packetStateData.serverTicksFrozen,
                    player.packetStateData.serverFrozenTickStepsRemaining
            )) {
                resetTimerWindows();
            }
        });
    }

    private void resetTimerWindows() {
        player.checkManager.getCheck(TimerCheck.class).resetTimerWindow();
        player.checkManager.getCheck(VehicleTimer.class).resetTimerWindow();
        player.checkManager.getListener(DumbTimer.class).resetTimerWindow();
    }

    private boolean isTickingNormally(float tickRate, boolean frozen, int frozenTickStepsRemaining) {
        return Math.abs(tickRate - 20.0F) < 1.0E-3F && (!frozen || frozenTickStepsRemaining > 0);
    }
}
