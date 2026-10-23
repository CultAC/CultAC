package ac.cult.cultac.checks.impl.elytra;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaFallDistance;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.nmsutil.RiptideUtil;

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
        if (lastPrediction == null
                || !lastPrediction
                        .getSimulationContext()
                        .getWorldData()
                        .getInWater()
                        .determinePessimistically()) return false;
        // LivingEntity#baseTick refreshes Entity#wasTouchingWater at the end of the last move, and after
        // TridentItem#releaseUsing's lift, before LocalPlayer#aiStep calls Player#tryToStartFallFlying.
        for (SimpleCollisionBox box : RiptideUtil.glideStartBoxes(player)) {
            if (!JavaFallDistance.touchesWater(player, box)) return false;
        }
        return true;
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (setback) {
            setback = false;
            setbackIfAboveSetbackVL();
        }
    }
}
