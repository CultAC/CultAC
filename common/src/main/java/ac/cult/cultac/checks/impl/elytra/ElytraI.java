package ac.cult.cultac.checks.impl.elytra;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;

@CheckData(
        name = "ElytraI",
        stableKey = "cult.elytra.water",
        description = "Started gliding in water",
        experimental = true)
public class ElytraI extends Check implements PostPredictionListener {
    private boolean setback;

    public ElytraI(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onPlayerCommand(
            PacketReceiveEvent<ServerboundPlayerCommand> event, CultPlayer player, ServerboundPlayerCommand packet) {
        if (packet.action() == PlayerCommandAction.START_FLYING_WITH_ELYTRA && wasTouchingWater() && flag()) {
            setback = true;
            if (shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
                player.resyncPose();
            }
        }
    }

    private boolean wasTouchingWater() {
        PredictionResult lastPrediction =
                player.checkManager.getSimulationProcessor().getLastPrediction();
        return lastPrediction != null
                && lastPrediction
                        .getSimulationContext()
                        .getWorldData()
                        .getInWater()
                        .determinePessimistically();
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (setback) {
            setback = false;
            setbackIfAboveSetbackVL();
        }
    }
}
