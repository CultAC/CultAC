package ac.grim.grimac.events.packets;

import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.checks.impl.movement.timer.DumbTimer;
import ac.grim.grimac.checks.impl.movement.timer.TimerCheck;
import ac.grim.grimac.checks.impl.movement.timer.VehicleTimer;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketSendEvent;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.game.ClientboundTickingStepPacket;
import net.minecraft.network.protocol.game.ClientboundTickingStatePacket;

public class PacketServerTickingState extends GrimProcessor implements CheckListener {
    public PacketServerTickingState(GrimPlayer player) {
        super(player);
    }

    @GrimPacketHandler
    public void onTickingState(PacketSendEvent event, GrimPlayer player, ClientboundTickingStatePacket packet) {
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

    @GrimPacketHandler
    public void onTickingStep(PacketSendEvent event, GrimPlayer player, ClientboundTickingStepPacket packet) {
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
